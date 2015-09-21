/*
 * Copyright 2011 DTO Solutions, Inc. (http://dtosolutions.com)
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

/*
* NodeGenerator.java
*
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: Oct 18, 2010 7:03:37 PM
*
*/
package com.normation.rundeck.plugin.resources.rudder

import org.apache.log4j.Logger
import com.dtolabs.rundeck.core.common.INodeEntry
import com.dtolabs.rundeck.core.common.NodeEntryImpl


object InstanceToNodeMapper {

  val logger = Logger.getLogger(InstanceToNodeMapper.getClass)
}

/**
 * Map Rudder nodes to Rundeck Ressources
 *
 */
class InstanceToNodeMapper(user: String, nodeRemoteUrl: NodeId => String) {

  /*
   * The only goal of that method is to take care of Rudder answers.
   * So it will analysis the answer, and based on the returned JSON,
   * it will map json to a Rudder node.
   */
  def mapNode(json: String): Either[ErrorMsg, Node] = {
    //for testing, return a node whatever the string
    val id = NodeId(json)
    Right(Node(
        id = id
      , nodename = json
      , hostname = json + ".foo.bar.com"
      , description = "desc. for " + json
      , osFamily = "Linux. Always"
      , osName = "Debian"
      , osArch = "x64"
      , osVersion = "21"
      , remoteUrl = nodeRemoteUrl(id)
      , rudderGroupTags = GroupId("group1") :: GroupId("group2") :: Nil
    ))
  }

  /*
   * Map a Rudder node to a Rundeck ressources.
   * Can't fail.
   */
  def nodeToRundeck(node: Node): INodeEntry = {
    val x = new NodeEntryImpl()

    x.setNodename(node.nodename)
    x.setNodename(node.hostname)
    x.setDescription(node.description)
    x.setOsFamily(node.osFamily)
    x.setOsArch(node.osArch)
    x.setOsVersion(node.osVersion)
    x.setUsername(user)
    x.getAttributes().put("editUrl", node.remoteUrl)
    x
  }


}
