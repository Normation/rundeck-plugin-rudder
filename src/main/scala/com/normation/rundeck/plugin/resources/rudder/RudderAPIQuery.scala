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

import rapture.core._
import rapture.core.timeSystems.numeric
import rapture.io._
import rapture.net._
import rapture.json._
import rapture.json.jsonBackends.jackson._

import com.dtolabs.rundeck.core.common.NodeEntryImpl

/*
 * This file manage the REST query logic. It's where queries
 * to Rudder server are made, and result parsed.
 *
 * There is only one implementation, so there is only one
 * object for that.
 *
 * The main methods are:
 * - queryNodes, which given the configuration will either query
 *   APIv4 or APIv6 for the list of node details.
 * - queryGroups, which get information about groups.
 *
 * That file does not contain the rundeck interface logic
 * (when query are done, how results are presented, etc).
 */
object RudderAPIQuery {

  /*
   * The list of sub-categories of details to include
   * on node information.
   */
  val topic = "?include=" + List(
      "environmentVariables" //to look for a specific user / port to use for rundeck
    , "networkInterfaces" // not sure
    , "storage" // not sure
    , "os" // basic os information
    , "properties" // rudder properties
    , "processessors" // not sure
    , "accounts" // not sure
    , "ipAddresses" // not sure
    , "fileSystems" //not sure
  ).mkString(",")


  /**
   * The main method to get nodes. It the one doing the indirection based on the
   * API Version to use.
   */
  def queryNodes(config: Configuration): Failable[Map[NodeId,NodeEntryImpl]] = {
    queryNodesDetails(config, config.url.nodesApi)
  }

  /**
   * Process a query returning a list of nodes. This is the optimized query
   * for API version 6: only one query, but it is the same logic (and hence the
   * same method) when you need to query one node details.
   */
  def queryNodesDetails(config: Configuration, queryUrl: String): Failable[Map[NodeId,NodeEntryImpl]] = {
    try {
      val url = Http.parse(queryUrl + topic)
      val page = url.get(
          timeout = config.apiTimeout.ms
        , ignoreInvalidCertificates = !config.checkCertificate
        , httpHeaders = Map("X-API-Token" -> config.apiToken
      )).slurp[Char]
      val json = Json.parse(page)

      if(json.result.as[String] == "success") {
        Traverse(json.data.nodes.as[Seq[Json]]) { node =>
         extractNode(node, config)
        }.fold( Left(_), x => Right(x.toMap) )
      } else {
        Left(ErrorMsg("Error when trying to get nodes: " + json.error.as[String]))
      }
    } catch {
      case ex: Exception => Left(ErrorMsg(s"Error when trying to get node(s) at url ${queryUrl}: " + ex.getMessage, Some(ex)))
    }
  }


  /**
   * For api v4, we need to make (number nodes) + 1 query to API !
   * This method handle the logic to do so.
   */
  def queryNodesV4(config: Configuration): Failable[Map[NodeId, NodeEntryImpl]] = {

    //only for extracting from JSON
    case class Node(id: String)

    try {
      val url = Http.parse(config.url.nodesApi)
      val page = url.get(
          timeout = config.apiTimeout.ms
        , ignoreInvalidCertificates = !config.checkCertificate
        , httpHeaders = Map("X-API-Token" -> config.apiToken
      )).slurp[Char]
      val json = Json.parse(page)

      if(json.result.as[String] == "success") {
        //ok, get all node ids

        val nodes = json.data.nodes.as[Seq[Node]]
        //now get nodes
        val jsons = nodes.par.map { case Node(id) =>
          queryNodesDetails(config, config.url.nodeApi(NodeId(id)))
        }.toVector

        Traverse(jsons) { identity }.fold( Left(_), x => Right(x.flatten.toMap) )
      } else {
        Left(ErrorMsg("Error when trying to get nodes: " + json.error.as[String]))
      }
    } catch {
      case ex: Exception => Left(ErrorMsg(s"Error when trying to get nodes at URL ${config.url.nodesApi}: " + ex.getMessage, Some(ex)))
    }
  }

  /**
   * Query for groups. The same for all API version.
   */
  def queryGroups(config: Configuration) : Failable[Seq[Group]] = {
    try {
      val url = Http.parse(config.url.groupsApi)
      val page = url.get(
          timeout = config.apiTimeout.ms
        , ignoreInvalidCertificates = !config.checkCertificate
        , httpHeaders = Map("X-API-Token" -> config.apiToken
      )).slurp[Char]
      val json = Json.parse(page)

      if(json.result.as[String] == "success") {
        Traverse(json.data.groups.as[Seq[Json]]) { group =>
         extractGroup(group)
        }
      } else {
        Left(ErrorMsg("Error when trying to get nodes: " + json.error.as[String]))
      }
    } catch {
      case ex: Exception => Left(ErrorMsg("Error when trying to get groups", Some(ex)))
    }
  }

  /**
   * Extract a group from what should be a JSON for group.
   */
  def extractGroup(json: Json) = {
    try {
      Right(Group(
          id = GroupId(json.id.as[String])
        , name = json.displayName.as[String]
        , nodeIds = json.nodeIds.as[Set[String]].map(NodeId(_))
        , enable = { //enable replaces isEnable in Rudder 4.0 API
                     import modes.returnTry
                     json.enable.as[Boolean].orElse(json.isEnabled.as[Boolean]).getOrElse(true)
                   }
        , dynamic = { //dynamic replaces isDynamic in Rudder 4.0 API
                      import modes.returnTry
                      json.dynamic.as[Boolean].orElse(json.isDynamic.as[Boolean]).getOrElse(false)
                    }
      ))
    } catch {
      case ex: Exception => Left(ErrorMsg("Error when trying to parse node information", Some(ex)))
    }

  }

  /**
   * This is where all the mapping logic between JSON (for node details) and
   * Rundeck "NodeEntry" object is done.
   * We don't use the interface, because we would must likelly not be compatible
   * with other implementations, and the groups part must be added later.
   * (i.e: it's our internals, it would be a false abstraction to try to hide
   * the actual implementation for the current state of the plugin).
   */
  def extractNode(json: Json, config: Configuration): Failable[(NodeId, NodeEntryImpl)] = {

    try {
      val id = NodeId(json.id.as[String])

      val env = try {
        json.environmentVariables.as[Map[String, String]]
      } catch {
        case ex: rapture.data.MissingValueException => Map[String,String]()
      }

      val (providedSSLPort , rundeckUser) = {
        (
            config.envVarSSLPort.flatMap { x => env.get(x) }
          , config.envVarRundeckUser.flatMap { x => env.get(x) }.getOrElse(config.rundeckDefaultUser)
        )
      }
      val node = new NodeEntryImpl()

      //all these one are mandatory
      node.setNodename(json.hostname.as[String] + " " + id.value)
      node.setHostname(json.hostname.as[String] + ":" + providedSSLPort.getOrElse(config.sshDefaultPort.toString))
      node.setOsName(json.os.name.as[String])
      node.setOsFamily(json.os.`type`.as[String])
      node.setOsArch(json.architectureDescription.as[String])
      node.setOsVersion(json.os.version.as[String])
      node.setUsername(rundeckUser)
      node.getAttributes().put("rudder_information:id", id.value)
      node.getAttributes().put("rudder_information:node_direct_url", config.url.nodeUrl(id))
      node.getAttributes().put("rudder_information:policy_server_id", json.policyServerId.as[String])
      node.getAttributes().put("rudder_information:last_inventory_date", json.lastInventoryDate.as[String])
      node.getAttributes().put("rudder_information:node_status", json.status.as[String])


      //these one are not - make as[XXX] return Try, so that it's easier to
      //only add relevant properties to the node object
      import modes.returnTry

      json.os.fullName.as[String].foreach( node.setDescription )
      json.ram.as[Int].foreach { x => node.getAttributes().put("total_ram", x.toString) }
      json.ipAddresses.as[List[String]].foreach { x => node.getAttributes().put("ip_addresses", x.mkString(", ")) }

      //rudder properties
      case class JsonRudderProp(name: String, value: String)
      json.properties.as[List[Option[JsonRudderProp]]].foreach { _.foreach { _.foreach { case JsonRudderProp(name, value) =>
        node.getAttributes().put(s"rudder_node_properties:${name}", value)
      } } }


      //accounts
      json.accounts.as[List[String]].foreach { accounts =>
        node.getAttributes().put("accounts_on_server", accounts.mkString(", "))
      }

      //env variables
      json.environmentVariables.as[Map[String, String]].foreach { _.foreach { case (k,v) =>
        node.getAttributes().put(s"rudder_environment_variables:${k}", v)
      } }

      //network
      json.networkInterfaces.as[Seq[Map[String, Json]]].foreach { _.foreach { case j =>
        j("name").as[String].foreach { name =>
          j("ipAddresses").as[Seq[String]].foreach { ips =>
            node.getAttributes().put(s"rudder_network_interface:${name}:ip_addresses", ips.mkString(", "))
          }
          j.filterKeys { k => k != "name" && k != "ipAddresses" }.foreach { case (k,v) =>
            v.as[String].orElse(v.as[Int]).orElse(v.as[Boolean]).foreach { value =>
              node.getAttributes().put(s"rudder_network_interface:${name}:${k}", value.toString)
            }
          }
        }
      } }

      //storage
      json.storage.as[Seq[Map[String, Json]]].foreach { _.foreach { case all =>
        //name must be there mandatory
        all("name").as[String].foreach { name =>
            all.filterKeys { k => k != "name" }.foreach { case (k,v) =>
              v.as[String].orElse(v.as[Int]).orElse(v.as[Boolean]).foreach { value =>
                node.getAttributes().put(s"rudder_storage:${name}:${k}", value.toString)
              }
            }
        }
      } }

      //file systems
      json.fileSystems.as[Seq[Map[String, Json]]].foreach { _.foreach { case all =>
        //name must be there mandatory
        all("name").as[String].foreach { name =>
            all.filterKeys { k => k != "name" }.foreach { case (k,v) =>
              v.as[String].orElse(v.as[Int]).orElse(v.as[Boolean]).foreach { value =>
                node.getAttributes().put(s"rudder_file_system:${name}:${k}", value.toString)
              }
            }
        }
      } }

      Right((id, node))

    } catch {
      case ex: Exception => Left(ErrorMsg("Error when trying to parse node information", Some(ex)))
    }
  }
}


