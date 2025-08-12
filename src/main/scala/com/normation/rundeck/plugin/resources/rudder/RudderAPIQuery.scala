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

import com.dtolabs.rundeck.core.common.NodeEntryImpl
import zio.{Chunk, ZIO}
import zio.http.{Client, FormField, Headers, QueryParams, Request, Status, URL}
import zio.http.Method.GET
import zio.json.{DeriveJsonEncoder, JsonDecoder}
import zio.json.DecoderOps
import zio.schema.{DeriveSchema, Schema}

/**
 * This file manages the REST query logic. This is where queries to the Rudder
 * server are made, and where the result is parsed.
 *
 * There is only one implementation, so there is only one object.
 *
 * The main methods are:
 *   - queryNodes, which gets information about nodes;
 *   - queryGroups, which gets information about groups.
 *
 * That file does not handle the rundeck interface logic (when the queries are
 * done, how the results are displayed, etc.).
 */
object RudderAPIQuery {

  /*
   * The list of sub-categories of details to include
   * on node information.
   */
  val topic = "include="

  val params: Chunk[String] = Chunk(
    "environmentVariables", // to look for a specific user / port to use for rundeck
    "networkInterfaces", // not sure
    "storage", // not sure
    "os", // basic os information
    "properties", // rudder properties
    "processessors", // not sure
    "accounts", // not sure
    "ipAddresses", // not sure
    "fileSystems" // not sure
  )

  /**
   * The main method to get nodes
   */
  def queryNodes(
      config: Configuration
  ): ZIO[Client, ErrorMsg, Map[NodeId, NodeEntryImpl]] = {

    val queryUrl = config.url.nodesApi
    val fullUrl =
      URL.decode(queryUrl + QueryParams(topic -> params).encode).toOption.get
    val headers = Headers(("X-API-Token" -> config.apiToken))
    println(fullUrl.path.encode)

    val request = Request(method = GET, url = fullUrl, headers = headers)

    Client
      .batched(request)
      .mapError(ex => {
        ErrorMsg(
          s"Error when trying to get node(s) at url ${fullUrl.encode}: " + ex.getMessage,
          Some(ex)
        )
      })
      .debug
      .flatMap(response => {
        response.status match
          case informational: Status.Informational => ???
          case success: Status.Success             =>
            response.body.asString.debug.orDie
          case redirection: Status.Redirection     => ???
          case error: Status.Error                 =>
            ZIO.fail(
              ErrorMsg(
                s"Error ${error.code} : ${error.reasonPhrase}",
                None
              )
            )
          case Status.Custom(code, reasonPhrase)   => ???
      })
      .flatMap(body => {
        body.fromJson[RudderNodeResponse] match
          case Left(errMsg)    => ZIO.fail(ErrorMsg(errMsg, None))
          case Right(nodeList) =>
            ZIO.succeed(
              nodeList.data.nodes
                .map(node => (NodeId(node.id), extractNode(node, config)))
                .toMap
            )
      })

  }

  /**
   * This is where all the mapping logic between JSON (for node details) and
   * Rundeck "NodeEntry" object is done. We don't use the interface, because we
   * would most likely not be compatible with other implementations, and the
   * groups part must be added later. (i.e: it's our internals, it would be a
   * false abstraction to try to hide the actual implementation for the current
   * state of the plugin).
   */
  def extractNode(
      rudderNode: Node,
      config: Configuration
  ): NodeEntryImpl = {

    val env = rudderNode.environmentVariables match
      case Some(value) => value.map(envVar => (envVar.name, envVar.value)).toMap
      case None        => Map.empty

    val rundeckUser = config.envVarRundeckUser
      .flatMap(v => env.get(v))
      .getOrElse(config.rundeckDefaultUser)

    val sslPort = config.envVarSSLPort
      .flatMap(v => env.get(v))
      .getOrElse(config.sshDefaultPort.toString)

    val node = NodeEntryImpl()

    // Mandatory attributes
    node.setNodename(rudderNode.hostname + " " + rudderNode.id)
    node.setHostname(rudderNode.hostname + ":" + sslPort)
    node.setOsName(rudderNode.os.name)
    node.setOsFamily(rudderNode.os.`type`)
    node.setOsArch(rudderNode.architectureDescription)
    node.setOsVersion(rudderNode.os.version)
    node.setUsername(rundeckUser)
    node.getAttributes.put("rudder_information:id", rudderNode.id)
    node.getAttributes.put(
      "rudder_information:node_direct_url",
      config.url.nodeUrl(NodeId(rudderNode.id))
    )
    node.getAttributes.put(
      "rudder_information:policy_server_id",
      rudderNode.policyServerId
    )
    node.getAttributes.put(
      "rudder_information:last_inventory_date",
      rudderNode.lastInventoryDate
    )
    node.getAttributes.put("rudder_information:node_status", rudderNode.status)

    // Optional attributes

    node.setDescription(rudderNode.os.fullName)
    rudderNode.ram.foreach { ram =>
      node.getAttributes.put("total_ram", ram.toString)
    }
    node.getAttributes.put(
      "ip_addresses",
      rudderNode.ipAddresses.mkString(", ")
    )
    rudderNode.properties.foreach { case Property(name, value) =>
      node.getAttributes.put(s"rudder_node_properties:${name}", value)
    }
    rudderNode.accounts.foreach { accounts =>
      node.getAttributes.put("accounts_on_server", accounts.mkString(", "))
    }
    rudderNode.environmentVariables.foreach { case EnvironmentVariable(k, v) =>
      node.getAttributes.put(s"rudder_environment_variables:${k}", v)
    }

    // network interfaces
    rudderNode.networkInterfaces.foreach(_.foreach { i =>
      i.name.foreach { name =>
        i.ipAddresses.foreach(ips => {
          node.getAttributes.put(
            s"rudder_network_interface:${name}:ip_addresses",
            ips.mkString(", ")
          )
        })
        i.toMap
          .filter((k, _) => k != "name" && k != "ipAddresses")
          .foreach { (k, v) =>
            node.getAttributes.put(
              s"rudder_network_interface:${name}:${k}",
              v.toString
            )
          }
      }
    })

    // storage
    rudderNode.storage.foreach(_.foreach { disk =>
      disk.name.foreach { name =>
        disk.toMap
          .filter((k, _) => k != "name")
          .foreach { (k, v) =>
            node.getAttributes.put(s"rudder_storage:${name}:${k}", v.toString)
          }
      }
    })

    // file systems
    rudderNode.fileSystems.foreach(_.foreach { fs =>
      fs.name.foreach { name =>
        fs.toMap
          .filter((k, _) => k != "name")
          .foreach { (k, v) =>
            node.getAttributes
              .put(s"rudder_file_system:${name}:${k}", v.toString)
          }
      }
    })

    node
  }

  /** Utility extension method to convert a case class into a map */
  extension (self: Any)
    def toMap: Map[String, Any] = {
      self.getClass.getDeclaredFields.foldLeft(Map.empty[String, Any]) {
        (a, f) =>
          f.setAccessible(true)
          a + (f.getName -> f.get(self))
      }
    }

  /**
   * Query for groups
   */
  def queryGroups(config: Configuration): ZIO[Client, ErrorMsg, Seq[Group]] = {
    ???
  }

  /**
   * Extract a group from what should be a JSON for group.
   */
  def extractGroup(json: Any) = {
    ???
  }
}
