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
import rapture.io._
import rapture.net._
import rapture.uri._
import rapture.json._
import rapture.codec._
import encodings.`UTF-8`
import jsonBackends.jackson._
import timeSystems.numeric
import scala.util.Left
import org.apache.log4j.Logger
import rapture.data.MissingValueException
import com.dtolabs.rundeck.core.common.NodeEntryImpl


/**
 * The part in charge to do REST query to Rudder
 * and parser resulting json
 */
object RudderAPIQuery {

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
    config.url.version match {
      case ApiV4 => queryNodesV4(config)
      case ApiV6 => queryNodesDetails(config, config.url.nodesApi)
    }
  }

  /**
   * Process a query returning a list of nodes. This is the optimized query
   * for API version 6: only one query, but it is the same processor when
   * you need to query one node details.
   */
  def queryNodesDetails(config: Configuration, queryUrl: String): Failable[Map[NodeId,NodeEntryImpl]] = {
    try {
      val url = Http.parse(queryUrl + topic)
      val page = url.get(timeout = 5000L, ignoreInvalidCertificates = !config.checkCertificate, httpHeaders = Map("X-API-Token" -> config.apiToken)).slurp[Char]
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
   *
   */
  def queryNodesV4(config: Configuration): Failable[Map[NodeId,NodeEntryImpl]] = {

    //only for extracting from JSON
    case class Node(id: String)

    try {
      val url = Http.parse(config.url.nodesApi)
      val page = url.get(timeout = 5000L, ignoreInvalidCertificates = !config.checkCertificate, httpHeaders = Map("X-API-Token" -> config.apiToken)).slurp[Char]
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
      val page = url.get(timeout = 5000L, ignoreInvalidCertificates = !config.checkCertificate, httpHeaders = Map("X-API-Token" -> config.apiToken)).slurp[Char]
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
  private[this] def extractGroup(json: Json) = {
    try {
      Right(Group(
          id = GroupId(json.id.as[String])
        , name = json.displayName.as[String]
        , nodeIds = json.nodeIds.as[Set[String]].map(NodeId(_))
        , enable = json.isEnabled.as[Boolean]
        , dynamic = json.isDynamic.as[Boolean]
      ))
    } catch {
      case ex: Exception => Left(ErrorMsg("Error when trying to parse node information", Some(ex)))
    }

  }

  private[this] def extractNode(json: Json, config: Configuration): Failable[(NodeId, NodeEntryImpl)] = {

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
      val x = new NodeEntryImpl()

      x.setNodename(json.hostname.as[String] + " " + id.value)
      x.setHostname(json.hostname.as[String] + ":" + providedSSLPort.getOrElse(config.sshDefaultPort.toString))
      x.setDescription(json.os.fullName.as[String])
      x.setOsName(json.os.name.as[String])
      x.setOsFamily(json.os.`type`.as[String])
      x.setOsArch(json.architectureDescription.as[String])
      x.setOsVersion(json.os.version.as[String])
      x.setUsername(rundeckUser)

      x.getAttributes().put("Total RAM", json.ram.as[Int].toString)
      x.getAttributes().put("IP Addresses", json.ipAddresses.as[List[String]].mkString(", "))
      json.accounts.as[Option[List[String]]].foreach { accounts =>
        x.getAttributes().put("Accounts on server", accounts.mkString(", "))
      }

      x.getAttributes().put("rudder_information:id", id.value)
      x.getAttributes().put("rudder_information:node direct URL", config.url.nodeUrl(id))
      x.getAttributes().put("rudder_information:policy server id", json.policyServerId.as[String])
      x.getAttributes().put("rudder_information:last inventory date", json.lastInventoryDate.as[String])
      x.getAttributes().put("rudder_information:node status", json.status.as[String])



      //env variables
      json.environmentVariables.as[Option[Map[String, String]]].foreach { _.foreach { case (k,v) =>
        x.getAttributes().put(s"rudder_environment_variables:${k}", v)
      } }

      //network
      json.networkInterfaces.as[Option[Seq[Json]]].foreach { _.foreach { case j =>
        val all = j.as[Map[String, Json]]
        val name = all("name").as[String]
        val ips = all("ipAddresses").as[Seq[String]].mkString(", ")

        x.getAttributes().put(s"rudder_network_interface:${name}:IP addresses", ips)
        all.filterKeys { k => k != "name" && k != "ipAddresses" }.foreach { case (k,v) =>
          x.getAttributes().put(s"rudder_network_interface:${name}:${k}", v.as[String])
        }
      } }



      //storage
      json.storage.as[Option[Seq[Map[String, Any]]]].foreach { _.foreach { case all =>
        val name = all("name") //mandatory
        all.filterKeys { k => k != "name" }.foreach { case (k,v) =>
          val value = v match { case z:String => z; case _ => v.toString }
          x.getAttributes().put(s"rudder_storage:${name}:${k}", value)
        }
      } }

      //file systems
      json.fileSystems.as[Option[Seq[Map[String, Any]]]].foreach { _.foreach { case all =>
        val name = all("name") //mandatory
        all.filterKeys { k => k != "name" }.foreach { case (k,v) =>
          val value = v match { case z:String => z; case _ => v.toString }
          x.getAttributes().put(s"rudder_file_system:${name}:${k}", value)
        }
      } }


      Right((id, x))

    } catch {
      case ex: Exception => Left(ErrorMsg("Error when trying to parse node information", Some(ex)))
    }
  }
}

object MainTest {
  def main(args: Array[String]): Unit = {

   val configV6 = Configuration(
       RudderUrl("https://192.168.46.2/rudder", ApiV6)
     , "WHFMJwnOl9kxegoDOjUBB7xrunWLqdTe"
     , TimeoutInterval(5), false, TimeoutInterval(30)
     , 22, Some("PERIOD")
     ,  "rundeck", Some("SUDO_USER")
   )

   val configV4 = Configuration(
       RudderUrl("https://orchestrateur-4.labo.normation.com/rudder", ApiV4)
     , "dTxvl4eL8p3YqvwefVbaJLdy8DyEt7Vw"
     , TimeoutInterval(5), false, TimeoutInterval(30)
     , 22, Some("PERIOD")
     , "rundeck", Some("SUDO_USER")
   )


   def print[T](res: Failable[T]): Unit = res match {
     case Right(x) => println(x)
     case Left(ErrorMsg(msg, optEx)) =>
         println("Error for nodes: " + msg)
         optEx.foreach { _.printStackTrace }
   }

   val config = configV4
   print(RudderAPIQuery.queryNodes(config).fold(Left(_), x  => Right(x.map( _._2.getNodename ))))
   print(RudderAPIQuery.queryGroups(config).fold(Left(_), x  => Right(x.map( _.name))))

   val mgn = new RudderResourceModelSource(config)

   mgn.getNodes()

  }
}

