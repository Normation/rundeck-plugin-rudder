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
import sun.security.action.GetBooleanAction



sealed trait ApiVersion { def value: String }
case object ApiV4 extends ApiVersion { val value = "4" }

/*
 * Rationnal to not use "latest" in place of 6.
 * If you use latest with Rudder 2.11/3.0, you
 * will have a clear error, nothing works => change version.
 * Auto API upgrade are not relevant, since the plugin won't
 * take advantage of them without an update.
 */
case object ApiV6 extends ApiVersion { val value = "6" }


//Rudder base URL, for ex: https://my.company.com/rudder/
// version must be (4 or 5), or (latest or 6)
final case class RudderUrl(baseUrl: String, version: ApiVersion) {

  private[this] val url = if(baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.size - 1) else baseUrl

  //last time an update was done, in ms - local timezone (i.e what System.getCurrentTimemillis returns)
  private[this] var lastUpdate = 0L

  //utility method
  def nodesApi = s"${url}/api/${version.value}/nodes"
  def nodeApi(id: NodeId) = nodesApi + "/" + id.value
  def nodeUrl(id: NodeId) =  s"""${url}/secure/nodeManager/searchNodes#{"nodeId":"${id.value}"}"""

  /**
   * Query on groups is always "latest", since we have the same
   * information on API v4, 5, 6
   */
  def groupsApi = s"${url}/api/latest/groups"
  def groupApi(id: GroupId) = groupsApi + "/" + id.value
}

final case class TimeoutInterval(secondes: Int) {
  val ms = secondes * 1000
}

//of course, non value can be null
final case class Configuration(
    url               : RudderUrl
  , apiToken          : String
  , apiTimeout        : TimeoutInterval
  , checkCertificate  : Boolean
  , refreshInterval   : TimeoutInterval //time in ms, should never be < 5000ms
  , sshDefaultPort    : Int
  , envVarSSLPort     : Option[String]
  , rundeckDefaultUser: String
  , envVarRundeckUser : Option[String]
)



//Rudder node ID, used as key to synchro nodes
final case class NodeId(value: String)
final case class GroupId(value: String)

final case class RudderProp(name: String, value: String)


final case class Group(
    id     : GroupId
  , name   : String
  , nodeIds: Set[NodeId]
  , enable : Boolean
  , dynamic: Boolean
)

object RudderResourceModelSource {

  def fromProperties(prop: Properties): Failable[RudderResourceModelSource] = {
    import RudderResourceModelSourceFactory._
    def getProp(key: String): Failable[String] = {
      prop.getProperty(key) match {
        case null  => Left(ErrorMsg(s"The property for mandatory key '${key}' was not found"))
        case value => Right(value)
      }
    }
    def getIntProp(key: String) = getProp(key) match {
      case Left(x) => Left(x)
      case Right(x) => try {
                     Right(x.toInt)
                   } catch { case ex: Exception =>
                     Left(ErrorMsg(s"Error when converting ${key} to int value: '${x}'", Some(ex)))
                   }
    }
    def getBoolProp(key: String) = getProp(key) match {
      case Left(x) => Left(x)
      case Right(x) => try {
                     Right(x.toBoolean)
                   } catch { case ex: Exception =>
                     Left(ErrorMsg(s"Error when converting ${key} to boolean value: '${x}'", Some(ex)))
                   }
    }

    for {
      url        <- getProp(RUDDER_API_ENDPOINT).right
      token      <- getProp(API_TOKEN).right
      user       <- getProp(RUNDECK_USER).right
      timeout    <- getIntProp(API_TIMEOUT).right
      checkSSL   <- getBoolProp(API_CHECK_CERTIFICATE).right
      sshPort    <- getIntProp(SSH_PORT).right
      refresh    <- getIntProp(REFRESH_INTERVAL).right
      apiVersion <- getProp(API_VERSION).fold(
                      Left(_)
                    , x => x match {
                        case "4" => Right(ApiV4)
                        case "6" => Right(ApiV6)
                        case _ => Left(ErrorMsg(s"The API version '${x}' is not authorized, only accepting '4' and '6'"))
                    }).right
    } yield {
      val envVarSSLPort = getProp(ENV_VARIABLE_SSL_PORT).fold(_ => None, x => Some(x))
      val envVarUser = getProp(ENV_VARIABLE_RUNDECK_USER).fold(_ => None, x => Some(x))

      //for now, timeout on Rudder API after 5s.
      new RudderResourceModelSource(Configuration(
          RudderUrl(url, apiVersion), token, TimeoutInterval(timeout), checkSSL, TimeoutInterval(refresh), sshPort, envVarSSLPort, user, envVarUser
      ))
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
  private[this] var nodes  = Map[NodeId, NodeEntryImpl]()
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
    val n = nodeToRundeck(nodes, groups).toSet

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
          this.nodes= res
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
  def nodeToRundeck(nodes: Map[NodeId, NodeEntryImpl], groups: Map[GroupId,Group]): Iterable[INodeEntry] = {
    import scala.collection.JavaConverters._

    val groupByNode = getGroupForNode(groups)

    //add groups
    nodes.map { case (nodeId, node) =>
      val groups = groupByNode.get(nodeId).getOrElse(Seq()).map( _.name)
      //add groups to both rudder_information and tags.
      val tags = (node.getTags.asScala ++ groups)
      node.setTags(tags.asJava)
      node.getAttributes().put("rudder_information:groups", groups.mkString(","))
      node
    }
  }


  private[this] def getGroupForNode(groups: Map[GroupId, Group]): Map[NodeId, Seq[Group]] = {
    //group groups by nodes id.
    val groupByNodeId = groups.values.flatMap { g => g.nodeIds.map { n => (n,g) } }.toSeq.groupBy( _._1 ).toMap

    groupByNodeId.mapValues { _.map( _._2) }
  }

}




