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


//Rudder node ID, used as key to synchro nodes
final case class NodeId(value: String)
final case class GroupId(value: String)

final case class RudderProp(name: String, value: String)

//Result of a parsed nodes
final case class Node( //hooo, the nice strings!
    id             : NodeId
  , status         : String
  , hostname       : String
  , osFullName     : String
  , osFamily       : String
  , osName         : String
  , osArch         : String
  , osVersion      : String
  , remoteUrl      : String
  , rudderGroupTags: List[GroupId]
  , ipAddresses    : List[String]
  , rudderProperties: List[RudderProp]
  , kernelVersion: String
  , lastInventoryDate: String
  , ram: String
  , policyServerId: String
  , providedSSLPort: Option[String]
  , rundeckUser : String
)

final case class Group(
    id     : GroupId
  , name   : String
  , nodeIds: Set[NodeId]
  , enable : Boolean
  , dynamic: Boolean
)

/**
 * The part in charge to do REST query to Rudder
 * and parser resulting json
 */
object RudderAPIQuery {


  def queryNodes(config: Configuration): Failable[Seq[Node]] = {
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

    try {
      val url = Http.parse(config.url.nodesApi + topic)
      val page = url.get(timeout = 5000L, ignoreInvalidCertificates = true, httpHeaders = Map("X-API-Token" -> config.apiToken)).slurp[Char]
      val json = Json.parse(page)

      if(json.result.as[String] == "success") {
        Traverse(json.data.nodes.as[Seq[Json]]) { node =>
         extractNode(node, config)
        }
      } else {
        Left(ErrorMsg("Error when trying to get nodes: " + json.error.as[String]))
      }
    } catch {
      case ex: Exception => Left(ErrorMsg("Error when trying to get node", Some(ex)))
    }
  }

  def queryGroups(config: Configuration) : Failable[Seq[Group]] = {
    try {
      val url = Http.parse(config.url.groupsApi)
      val page = url.get(timeout = 5000L, ignoreInvalidCertificates = true, httpHeaders = Map("X-API-Token" -> config.apiToken)).slurp[Char]
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

  private[this] def extractNode(json: Json, config: Configuration) = {

    try {
      val id = NodeId(json.id.as[String])

      val env = try {
        json.environmentVariables.as[Map[String, String]]
      } catch {
        case ex: rapture.data.MissingValueException => Map[String,String]()
      }

      val (sslPort , user) = {
        (
            config.envVarSSLPort.flatMap { x => env.get(x) }
          , config.envVarRundeckUser.flatMap { x => env.get(x) }.getOrElse(config.rundeckUser)
        )
      }

      Right(Node(
          id = id
        , status      = json.status.as[String]
        , hostname    = json.hostname.as[String]
        , osFullName  = json.os.fullName.as[String]
        , osFamily    = json.os.`type`.as[String]
        , osName      = json.os.name.as[String]
        , osArch      = json.architectureDescription.as[String]
        , osVersion   = json.os.version.as[String]
        , remoteUrl   = config.url.nodeUrl(id)
        , rudderGroupTags = List[GroupId]() //to complete afterward
        , ipAddresses  = json.ipAddresses.as[List[String]]
        , rudderProperties = json.properties.as[List[RudderProp]]
        , kernelVersion = json.os.kernelVersion.as[String]
        , lastInventoryDate = json.lastInventoryDate.as[String]
        , ram = json.ram.as[Int].toString
        , policyServerId = json.policyServerId.as[String]
        , providedSSLPort = sslPort
        , rundeckUser = user
      ))
    } catch {
      case ex: Exception => Left(ErrorMsg("Error when trying to parse node information", Some(ex)))
    }
  }
}

//object MainTest {
//  def main(args: Array[String]): Unit = {
//
//   val config = Configuration(
//       RudderUrl("https://192.168.46.2/rudder")
//     , "WHFMJwnOl9kxegoDOjUBB7xrunWLqdTe"
//     , "rundeck"
//     , RefreshInterval(30)
//     , Some("PERIOD")
//     , Some("SUDO_USER")
//   )
//
//
//   println(RudderAPIQuery.queryNodes(config).fold(identity , x  => x.map( _.hostname)))
//   println(RudderAPIQuery.queryGroups(config).fold(identity , x  => x.map( _.name)))
//
//
//  }
//}

