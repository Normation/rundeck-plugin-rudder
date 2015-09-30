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


/**
 * This is the entry point for one Rudder provisionning.
 * It is responsible for the whole querying and mapping of Rudder nodes
 * to Rundeck resources.
 * Here, we have mostly glue. The actual querying and mapping is done in
 * dedicated methods.
 */
class RudderResourceModelSource(val configuration: Configuration) extends ResourceModelSource {

  lazy val logger = Logger.getLogger(this.getClass)

  //we are locally caching nodes and groups instances.
  private[this] var nodes  = Map[NodeId, NodeEntryImpl]()
  private[this] var groups = Map[GroupId, Group]()

  //last time, in ms, that nodes and groups were update (result of System.getCurrentTimeMillis)
  private[this] var lastUpdateTime = 0L


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




