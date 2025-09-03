/*
 * Copyright 2025 Normation (http://normation.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.normation.rundeck.plugin.resources.rudder

import com.dtolabs.rundeck.core.common.INodeEntry
import com.dtolabs.rundeck.core.common.INodeSet
import com.dtolabs.rundeck.core.common.NodeEntryImpl
import com.dtolabs.rundeck.core.common.NodeSetImpl
import com.dtolabs.rundeck.core.resources.ResourceModelSource
import com.dtolabs.rundeck.core.resources.ResourceModelSourceException
import java.net.Socket
import java.net.http.HttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import org.slf4j.LoggerFactory
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.httpclient.zio.SttpClient
import sttp.client4.logging.slf4j.Slf4jLoggingBackend
import zio.Ref
import zio.UIO
import zio.Unsafe
import zio.ZIO
import zio.ZLayer

/**
 * This is the entry point for one Rudder provisioning. It is responsible for
 * the whole querying and mapping of Rudder nodes to Rundeck resources. Here, we
 * mostly have glue. The actual querying and mapping is done in dedicated
 * methods.
 */
class RudderResourceModelSource(val configuration: Configuration)
    extends ResourceModelSource {

  private val logger = RudderLogger(LoggerFactory.getLogger(this.getClass))

  // we are locally caching nodes and groups instances.
  private val nodes: Ref[INodeSet] =
    Ref.make(new NodeSetImpl()).unsafeRun

  // last time, in ms, that nodes and groups were update (result of System.getCurrentTimeMillis)
  private val lastUpdateTime = Ref.make(0L).unsafeRun

  /**
   * This is the actual, only integration point with Rundeck. The logic is to
   * cache nodes for some time, to avoid too many request to Rudder (especially
   * on v4 API)
   */
  @throws(classOf[ResourceModelSourceException])
  override def getNodes: INodeSet =

    val backend =
      if (configuration.checkCertificate)
        HttpClientZioBackend.layer()
      else {
        val ssl = SSLContext.getInstance("TLS")
        val trustManager = RudderResourceModelSource.DangerAcceptInvalidCerts
        ssl.init(null, Array(trustManager), new SecureRandom)

        val httpClient = HttpClient.newBuilder().sslContext(ssl).build()

        ZLayer.scoped {
          HttpClientZioBackend
            .layerUsingClient(httpClient)
            .build
            .map(l => Slf4jLoggingBackend(l.get[SttpClient]))
        }
      }

    updateNodesAndGroups()
      .provideLayer(backend.orDie)
      .unsafeRun

  extension [A](self: UIO[A])
    def unsafeRun: A =
      Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe
          .run(self)
          .getOrThrowFiberFailure()
      }

  extension (self: Map[NodeId, NodeEntryImpl])
    def toRundeckNodeSet: INodeSet = {
      import scala.jdk.CollectionConverters._
      val set = new NodeSetImpl()
      set.putNodes(self.values.toSet[INodeEntry].asJava)
      set
    }

  /**
   * Update the local node cache if needed
   */
  private def updateNodesAndGroups(): ZIO[SttpClient, Nothing, INodeSet] =

    for {
      now <- ZIO.clockWith(_.currentTime(TimeUnit.MILLISECONDS))
      lastUpdate <- this.lastUpdateTime.get
      shouldRefresh = lastUpdate + configuration.refreshInterval.ms < now
      _ <- logger.debug(
        s"current time : ${now} ;" +
          s"time of last update : ${lastUpdate} ;" +
          s"should nodes be refreshed ? : ${shouldRefresh}"
      )

      doUpdate = getNodesFromRudder(configuration)
        .foldZIO(
          errorMsg => // do not update cache
            logger.error(
              s"Error when trying to get new node information from Rudder: ${errorMsg.value}"
            ) *>
              ZIO.whenCaseDiscard(errorMsg.exception) { case Some(ex) =>
                logger.error("Root exception was: ", ex)
              },
          n =>
            // lastUpdateTime is only updated if the nodes were successfully retrieved.
            // Hence, if an error occurred, the update will be attempted again on the next call.
            this.lastUpdateTime.set(now)
              *> logger.info(
                s"Successfully imported ${n.size} Rudder node(s) with id(s) : "
                  + n.keys.mkString(", ")
              )
              *> this.nodes.set(n.toRundeckNodeSet)
        )

      postponeUpdate =
        logger.debug(
          s"Not updating nodes and groups because refresh interval of ${configuration.refreshInterval.seconds}s was not elapsed since last update."
        )

      _ <-
        if (shouldRefresh)
          logger.debug("Getting nodes from Rudder")
            *> doUpdate
        else postponeUpdate

      nodes <- this.nodes.get

    } yield nodes

  /**
   * This method unconditionally gets new nodes from Rudder. It builds the
   * corresponding Rundeck nodes, and adds the Rudder groups as tags.
   */
  private def getNodesFromRudder(
      config: Configuration
  ): ZIO[SttpClient, ErrorMsg, Map[NodeId, NodeEntryImpl]] =

    // not sure if it's better to not update at all if I don't get groups (like here)
    // or keep the old groups with new node infos (I think no), or put empty groups (not sure).
    for {
      (groups, newNodes) <-
        RudderAPIQuery.queryGroups(config)
          <&> RudderAPIQuery.queryNodes(config)
    } yield {
      import scala.jdk.CollectionConverters._
      val groupByNode = RudderResourceModelSource.getGroupForNode(groups)
      // add groups
      newNodes.map { case (nodeId, node) =>
        val groups = groupByNode.getOrElse(nodeId, Seq()).map(_.displayName)
        // add groups to both rudder_information and tags.
        val tags = (node.getTags.asScala ++ groups)
        node.setTags(tags.asJava)
        node.getAttributes.put(
          "rudder_information:groups",
          groups.mkString(",")
        )
        (nodeId, node)
      }
    }

}

object RudderResourceModelSource {

  private val DangerAcceptInvalidCerts: X509TrustManager =
    new X509ExtendedTrustManager():
      override def getAcceptedIssuers: Array[X509Certificate] =
        Array.empty[X509Certificate]

      override def checkServerTrusted(
          x509Certificates: Array[X509Certificate],
          s: String
      ): Unit = ()

      override def checkClientTrusted(
          x509Certificates: Array[X509Certificate],
          s: String
      ): Unit = ()

      override def checkClientTrusted(
          x509Certificates: Array[X509Certificate],
          s: String,
          socket: Socket
      ): Unit = ()

      override def checkServerTrusted(
          x509Certificates: Array[X509Certificate],
          s: String,
          socket: Socket
      ): Unit = ()

      override def checkClientTrusted(
          x509Certificates: Array[X509Certificate],
          s: String,
          sslEngine: SSLEngine
      ): Unit = ()

      override def checkServerTrusted(
          x509Certificates: Array[X509Certificate],
          s: String,
          sslEngine: SSLEngine
      ): Unit = ()

  def getGroupForNode(
      groups: Seq[Group]
  ): Map[NodeId, Seq[Group]] = {
    // group groups by nodes id.
    val groupByNodeId =
      groups.flatMap(g => g.nodeIds.map(n => (n, g))).groupBy(_._1)
    groupByNodeId.view.mapValues(_.map(_._2)).toMap
  }
}
