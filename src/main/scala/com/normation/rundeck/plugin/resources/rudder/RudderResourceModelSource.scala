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

  private[this] lazy val logger = Logger.getLogger(this.getClass)

  //we are locally caching nodes and groups instances.
  private[this] var nodes  = Map[NodeId, NodeEntryImpl]()

  //last time, in ms, that nodes and groups were update (result of System.getCurrentTimeMillis)
  private[this] var lastUpdateTime = 0L


  /**
   * This is the actual, only integration point with Rundeck.
   * The logic is to cache nodes for some time, to avoid too
   * many request to Rudder (especially on v4 API)
   */
  @throws(classOf[ResourceModelSourceException])
  override def getNodes(): INodeSet = {
    //update nodes and groups if needed

    logger.debug("Getting nodes from Rudder")
    updateNodesAndGroups()

    import scala.collection.JavaConverters._
    val set = new NodeSetImpl()
    set.putNodes(nodes.values.toSet[INodeEntry].asJava)
    set

  }

  //unit is saying that the methods handle both the update and
  //logging/managing errors
  def updateNodesAndGroups(): Unit = {
    val now = System.currentTimeMillis()

    if(this.lastUpdateTime + configuration.refreshInterval.ms < now) {
      getNodesFromRudder(configuration) match {
        case Left(ErrorMsg(msg, optEx)) =>
          //do not update cache
          logger.error(s"Error when trying to get new nodes information from Rudder: ${msg}")
          optEx.foreach { ex =>
            logger.error("Root exception was: ", ex)
          }
        case Right(n) =>
          //we only update time here, meaning that each time there is an error,
          //we will try again next time.
          //that may lead to funny loop when there is always error and user query quickly the
          //method, but the user would not understand if he repairs an error on Rudder, and
          //things don't work immediately.
          this.lastUpdateTime = now
          this.nodes = n
      }
    } else {
      logger.debug(s"Not updating nodes and groups because refresh interval of ${configuration.refreshInterval.secondes}s was not elapsed since last update.")
    }
  }

  /**
   * This method unconditionnaly get new nodes from Rudder.
   * It builds a the resulting rundeck nodes, and in particular
   * add groups as tag
   */
  def getNodesFromRudder(config: Configuration): Failable[Map[NodeId, NodeEntryImpl]] = {

    for {
      groups   <- RudderAPIQuery.queryGroups(config).right
      newNodes <- RudderAPIQuery.queryNodes(config).right
    } yield {
      import scala.collection.JavaConverters._
      val groupByNode = getGroupForNode(groups)
      //add groups
      newNodes.map { case (nodeId, node) =>
        val groups = groupByNode.get(nodeId).getOrElse(Seq()).map( _.name)
        //add groups to both rudder_information and tags.
        val tags = (node.getTags.asScala ++ groups)
        node.setTags(tags.asJava)
        node.getAttributes().put("rudder_information:groups", groups.mkString(","))
        (nodeId, node)
      }.toMap
    }
  }

  private[this] def getGroupForNode(groups: Seq[Group]): Map[NodeId, Seq[Group]] = {
    //group groups by nodes id.
    val groupByNodeId = groups.flatMap { g => g.nodeIds.map { n => (n,g) } }.toSeq.groupBy( _._1 ).toMap
    groupByNodeId.mapValues { _.map( _._2) }
  }

}




