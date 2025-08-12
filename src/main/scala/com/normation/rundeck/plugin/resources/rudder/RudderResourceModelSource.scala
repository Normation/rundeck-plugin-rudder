/*
 * Copyright 2015 Normation (http://normation.com)
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

import com.dtolabs.rundeck.core.resources.ResourceModelSource
import com.dtolabs.rundeck.core.resources.ResourceModelSourceException
import com.dtolabs.rundeck.core.common.INodeSet
import com.dtolabs.rundeck.core.common.NodeSetImpl
import com.dtolabs.rundeck.core.common.INodeEntry
import com.dtolabs.rundeck.core.common.NodeEntryImpl
import org.slf4j.LoggerFactory
import zio.http.Client
import zio.{IO, UIO, Unsafe, ZIO}

/**
 * This is the entry point for one Rudder provisionning. It is responsible for
 * the whole querying and mapping of Rudder nodes to Rundeck resources. Here, we
 * have mostly glue. The actual querying and mapping is done in dedicated
 * methods.
 */
class RudderResourceModelSource(val configuration: Configuration)
    extends ResourceModelSource {

  private lazy val logger = LoggerFactory.getLogger(this.getClass)

  // we are locally caching nodes and groups instances.
  private var nodes: INodeSet = new NodeSetImpl()

  // last time, in ms, that nodes and groups were update (result of System.getCurrentTimeMillis)
  private var lastUpdateTime = 0L

  /**
   * This is the actual, only integration point with Rundeck. The logic is to
   * cache nodes for some time, to avoid too many request to Rudder (especially
   * on v4 API)
   */
  @throws(classOf[ResourceModelSourceException])
  override def getNodes: INodeSet = {
    logger.debug("Getting nodes from Rudder")
    updateNodesAndGroups()
      .provide(Client.default.orDie)
      .unsafeRun
  }

  extension [A](self: UIO[A])
    def unsafeRun: A = {
      Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe
          .run(self)
          .getOrThrowFiberFailure()
      }
    }

  extension (self: Map[NodeId, NodeEntryImpl])
    def toRundeckNodeSet: INodeSet = {
      import scala.jdk.CollectionConverters._
      val set = new NodeSetImpl()
      set.putNodes(self.values.toSet[INodeEntry].asJava)
      set
    }

  /**
   * Update the local node cache is needed
   */
  private def updateNodesAndGroups(): ZIO[Client, Nothing, INodeSet] = {
    val now = System.currentTimeMillis()

    val doUpdate = getNodesFromRudder(configuration)
      .fold(
        errorMsg => { // do not update cache
          logger.error(
            s"Error when trying to get new nodes information from Rudder: ${errorMsg.value}"
          )
          errorMsg.exception.foreach { ex =>
            logger.error("Root exception was: ", ex)
          }
        },
        n => {
          // we only update time here, meaning that each time there is an error,
          // we will try again next time.
          // that may lead to funny loop when there is always error and user query quickly the
          // method, but the user would not understand if he repairs an error on Rudder, and
          // things don't work immediately.
          this.lastUpdateTime = now
          this.nodes = n.toRundeckNodeSet
        }
      )

    val postponeUpdate = {
      logger.debug(
        s"Not updating nodes and groups because refresh interval of ${configuration.refreshInterval.secondes}s was not elapsed since last update."
      )
      ZIO.unit
    }

    (if (this.lastUpdateTime + configuration.refreshInterval.ms < now) doUpdate
     else postponeUpdate)
      .as(this.nodes)
  }

  /**
   * This method unconditionally gets new nodes from Rudder. It builds the
   * corresponding Rundeck nodes, and adds the Rudder groups as tags.
   */
  private def getNodesFromRudder(
      config: Configuration
  ): ZIO[Client, ErrorMsg, Map[NodeId, NodeEntryImpl]] = {

    // not sure if it's better to not update at all if I don't get groups (like here)
    // or keep the old groups with new node infos (I think no), or put empty groups (not sure).
    for {
      groups <- Seq.empty[Group].succeed
      newNodes <- RudderAPIQuery.queryNodes(config)
    } yield {
      import scala.jdk.CollectionConverters._
      val groupByNode = getGroupForNode(groups)
      // add groups
      newNodes.map { case (nodeId, node) =>
        val groups = groupByNode.getOrElse(nodeId, Seq()).map(_.name)
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

  private def getGroupForNode(
      groups: Seq[Group]
  ): Map[NodeId, Seq[Group]] = {
    // group groups by nodes id.
    val groupByNodeId =
      groups.flatMap { g => g.nodeIds.map { n => (n, g) } }.groupBy(_._1)
    groupByNodeId.view.mapValues { _.map(_._2) }.toMap
  }

}
