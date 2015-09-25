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

import java.util.Properties
import com.dtolabs.rundeck.core.resources.ResourceModelSource
import com.dtolabs.rundeck.core.resources.ResourceModelSourceException
import com.dtolabs.rundeck.core.common.INodeSet
import com.dtolabs.rundeck.core.common.NodeSetImpl
import com.dtolabs.rundeck.core.common.INodeEntry
import com.dtolabs.rundeck.core.common.NodeEntryImpl

import scala.util.Left
import org.apache.log4j.Logger


//Rudder base URL, for ex: https://my.company.com/rudder/
final case class RudderUrl(baseUrl: String) {

  /*
   * We are only compatible with API v6 and above
   * Before v6, we can't have information on all nodes in
   * one request.
   */
  private[this] val v = "6"
  private[this] val url = if(baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.size - 1) else baseUrl

  //last time an update was done, in ms - local timezone (i.e what System.getCurrentTimemillis returns)
  private[this] var lastUpdate = 0L

  //utility method
  def nodesApi = s"${url}/api/${v}/nodes"
  def nodeApi(id: NodeId) = nodesApi + "/" + id.value
  def nodeUrl(id: NodeId) =  s"""${url}/secure/nodeManager/searchNodes#{"nodeId":"${id.value}"}"""

  def groupsApi = s"${url}/api/${v}/groups"
  def groupApi(id: GroupId) = groupsApi + "/" + id.value
}

final case class RefreshInterval(secondes: Int) {
  val ms = secondes * 1000
}

//of course, non value can be null
final case class Configuration(
    url: RudderUrl
  , apiToken: String
  , rundeckUser: String
  , refreshInterval: RefreshInterval //time in ms, should never be < 5000ms

  , envVarSSLPort: Option[String]
  , envVarRundeckUser: Option[String]
)



object RudderResourceModelSource {

  def fromProperties(prop: Properties): Failable[RudderResourceModelSource] = {
    import RudderResourceModelSourceFactory._
    def getProp(key: String): Failable[String] = {
      prop.getProperty(key) match {
        case null  => Left(ErrorMsg(s"The property for key '${key}' was not found"))
        case value => Right(value)
      }
    }

    for {
      url     <- getProp(RUDDER_API_ENDPOINT).right
      token   <- getProp(API_TOKEN).right
      user    <- getProp(RUNDECK_USER).right
      refresh <- {
                   val _r =  getProp(REFRESH_INTERVAL).fold(_ => "30", identity)
                   try {
                     Right(RefreshInterval(_r.toInt))
                   } catch {
                     case ex: Exception =>
                       Left(ErrorMsg(s"Error when converting refresh rate to int value: '${}=${}'", Some(ex)))
                   }
                 }.right
    } yield {
      val envVarSSLPort = getProp(ENV_VARIABLE_RUNDECK_USER).fold(_ => None, x => Some(x))
      val envVarUser = getProp(ENV_VARIABLE_RUNDECK_USER).fold(_ => None, x => Some(x))

      new RudderResourceModelSource(Configuration(RudderUrl(url), token, user, refresh, envVarSSLPort, envVarUser))
    }
  }

}

/**
 * This is the entry point for one Rudder provisionning.
 * It is responsible for the whole querying and mapping of Rudder nodes
 * to Rundeck resources.
 * Here, we have mostly glue. The actual querying and mapping is done in
 * dedicated methods.
 */
class RudderResourceModelSource(val configuration: Configuration) extends ResourceModelSource {

  //we are locally caching nodes and groups instances.
  private[this] var nodes  = Map[NodeId, Node]()
  private[this] var groups = Map[GroupId, Group]()

  //last time, in ms, that nodes and groups were update (result of System.getCurrentTimeMillis)
  private[this] var lastUpdateTime = 0L

  private[this] val logger = Logger.getLogger("rudder")

  @throws(classOf[ResourceModelSourceException])
  def getNodes(): INodeSet = {
    //update nodes and groups if needed

    logger.debug("Getting nodes from Rudder")
    updateNodesAndGroups()

    //just map nodes to
    val n = nodeToRundeck(nodes.values, groups.values).toSet

    import scala.collection.JavaConverters._
    val set = new NodeSetImpl()
    set.putNodes(n.asJava)
    set

  }

  //unit is saying that the methods handle both the update and
  //logging/managing errors
  def updateNodesAndGroups(): Unit = {
    val now = System.currentTimeMillis()

    if(this.lastUpdateTime + configuration.refreshInterval.ms < now) {
      this.lastUpdateTime = now
      RudderAPIQuery.queryGroups(configuration) match {
        case Right(res) =>
          logger.debug(s"Found ${res.size} groups on Rudder server")
          this.groups = res.map { x => (x.id, x) }.toMap
        case Left(ErrorMsg(msg, optEx)) =>
          logger.error(s"Error when trying to update the groups from Rudder at url ${configuration.url.groupsApi}: ${msg}")
          optEx.foreach { ex =>
            logger.error("Exception was: ", ex)
          }
      }

      RudderAPIQuery.queryNodes(configuration) match {
        case Right(res) =>
          logger.debug(s"Found ${res.size} nodes on Rudder server")
          this.nodes= res.map { x => (x.id, x) }.toMap
        case Left(ErrorMsg(msg, optEx)) =>
          logger.error(s"Error when trying to update the nodes from Rudder at url ${configuration.url.nodesApi}: ${msg}")
          optEx.foreach { ex =>
            logger.error("Exception was: ", ex)
          }
      }
    } else {
      logger.debug(s"Not updating nodes and groups because refresh interval of ${configuration.refreshInterval.secondes}s was not elapsed since last update.")
    }
  }

  /*
   * Map a Rudder node to a Rundeck ressources.
   * Can't fail <== are we sure ?
   */
  def nodeToRundeck(nodes: Iterable[Node], groups: Iterable[Group]): Iterable[INodeEntry] = {

    //group groups by nodes id.
    val groupByNodeId = groups.flatMap { g => g.nodeIds.map { n => (n,g) } }.toSeq.groupBy( _._1 ).toMap

    def groupForNode(nodeId: NodeId) : Seq[Group] = {
      groupByNodeId.get(nodeId) match {
        case None => Seq()
        case Some(x) => x.map( _._2 )
      }
    }

    nodes.map { node =>
      val x = new NodeEntryImpl()

      x.setNodename(node.hostname + " " + node.id.value)
      x.setHostname(node.hostname+node.providedSSLPort.map(x => ":"+x).getOrElse(""))
      x.setDescription(node.osFullName)
      x.setOsName(node.osName)
      x.setOsFamily(node.osFamily)
      x.setOsArch(node.osArch)
      x.setOsVersion(node.osVersion)
      x.setUsername(node.rundeckUser)
      x.getAttributes().put("rudder_information:node direct URL", node.remoteUrl)
      x.getAttributes().put("rudder_information:groups", groupForNode(node.id).map( _.name).mkString(","))
      x.getAttributes().put("rudder_information:id", node.id.value)
      x.getAttributes().put("rudder_information:policy_server_id", node.policyServerId)
      x.getAttributes().put("rudder_general:ram", node.ram)
      x.getAttributes().put("rudder_networks:ips", node.ipAddresses.mkString(", "))
      x
    }
  }

}




