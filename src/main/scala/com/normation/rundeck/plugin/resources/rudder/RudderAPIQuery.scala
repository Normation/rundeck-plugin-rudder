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
import org.slf4j.LoggerFactory
import zio.Chunk
import zio.IO
import zio.ZIO
import zio.http.Client
import zio.http.Headers
import zio.http.Method.GET
import zio.http.QueryParams
import zio.http.Request
import zio.http.Response
import zio.http.Status
import zio.http.URL
import zio.json.DecoderOps
import zio.json.ast.Json

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

  private lazy val logger = LoggerFactory.getLogger(this.getClass)

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
    val request = Request(method = GET, url = fullUrl, headers = headers)

    for {
      response <- Client
        .batched(request)
        .mapError(ex =>
          ErrorMsg(
            s"Error when trying to get node(s) at url ${fullUrl.encode}: " + ex.getMessage,
            Some(ex)
          )
        )

      body <- response.status match
        case success: Status.Success =>
          response.body.asString.orDie
        case error: Status.Error     =>
          ErrorMsg(s"Error ${error.code} : ${error.reasonPhrase}").fail
        case status: Status          =>
          val errMsg =
            s"Unsupported response status code : ${status.code} ; details : ${status.reasonPhrase}"
          ErrorMsg(errMsg).fail

      json <- body.fromJson[RudderNodeResponse] match
        case Left(errMsg)    => ErrorMsg(errMsg).fail
        case Right(nodeList) => nodeList.data.nodes.succeed

      map <- ZIO.foldLeft(json)(Map.empty[NodeId, NodeEntryImpl])((map, node) =>
        extractNode(node, config)
          .map { nodeEntry =>
            logger.info(
              s"Successfully imported Rudder node with id \'${node.id}\'."
            )
            map + ((NodeId(node.id), nodeEntry))
          }
          .mapError { err =>
            logger.error(
              s"Error during import of Rudder node with id \'${node.id}\' : ${err.value}"
            )
            err
          }
      )
    } yield map

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
  ): ZIO[Any, ErrorMsg, NodeEntryImpl] = {

    for {
      (os, architectureDescription, policyServerId, lastInventoryDate) <- (
        rudderNode.os,
        rudderNode.architectureDescription,
        rudderNode.policyServerId,
        rudderNode.lastInventoryDate
      ) match
        case (Some(o), Some(a), Some(p), Some(l)) =>
          (o, a, p, l).succeed
        case (o, a, p, l)                         =>
          Seq(
            o.toRequiredFieldError("os"),
            a.toRequiredFieldError("architectureDescription"),
            p.toRequiredFieldError("policyServerId"),
            l.toRequiredFieldError("lastInventoryDate")
          ).foldLeft(
            ErrorMsg(
              s"Rudder node with id \'${rudderNode.id}\' is missing one or more required fields : "
            )
          )((accErrMsg, nextErrMsgOpt) =>
            nextErrMsgOpt match
              case Some(nextErrMsg) => accErrMsg.append(nextErrMsg)
              case None             => accErrMsg
          ).fail
    } yield {

      val env = rudderNode.environmentVariables match
        case Some(map) => map
        case None      => Map.empty

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
      node.setOsName(os.name)
      node.setOsFamily(os.`type`)
      node.setOsArch(architectureDescription)
      node.setOsVersion(os.version)
      node.setUsername(rundeckUser)
      node.getAttributes.put("rudder_information:id", rudderNode.id)
      node.getAttributes.put(
        "rudder_information:node_direct_url",
        config.url.nodeUrl(NodeId(rudderNode.id))
      )
      node.getAttributes.put(
        "rudder_information:policy_server_id",
        policyServerId
      )
      node.getAttributes.put(
        "rudder_information:last_inventory_date",
        lastInventoryDate
      )
      node.getAttributes.put(
        "rudder_information:node_status",
        rudderNode.status
      )

      // Optional attributes

      node.setDescription(os.fullName)
      rudderNode.ram.foreach { ram =>
        node.getAttributes.put("total_ram", ram.toString)
      }
      node.getAttributes.put(
        "ip_addresses",
        rudderNode.ipAddresses.mkString(", ")
      )
      rudderNode.properties.foreach { case Property(name, value) =>
        node.getAttributes.put(s"rudder_node_properties:$name", value)
      }
      rudderNode.accounts.foreach { accounts =>
        node.getAttributes.put("accounts_on_server", accounts.mkString(", "))
      }
      env.map((k, v) =>
        node.getAttributes.put(s"rudder_environment_variables:$k", v)
      )

      // network interfaces
      rudderNode.networkInterfaces.foreach(_.foreach { i =>
        i.asObject.foreach(json =>
          json.get("name").flatMap(_.asString).foreach { name =>
            json
              .get("ipAddresses")
              .flatMap(_.as[Seq[String]].toOption)
              .foreach(ips =>
                node.getAttributes.put(
                  s"rudder_network_interface:$name:ip_addresses",
                  ips.mkString(", ")
                )
              )

            json
              .filterKeys(k => k != "name" && k != "ipAddresses")
              .fields
              .foreach { (k, v) =>
                v.asSimpleField.foreach(value =>
                  node.getAttributes.put(
                    s"rudder_network_interface:$name:$k",
                    value.toString
                  )
                )
              }
          }
        )
      })

      // storage
      rudderNode.storage.foreach(_.foreach { disk =>
        disk.asObject.foreach(json =>
          json.get("name").flatMap(_.asString).foreach { name =>
            json
              .filterKeys(k => k != "name")
              .fields
              .foreach { (k, v) =>
                v.asSimpleField.foreach(value =>
                  node.getAttributes
                    .put(s"rudder_storage:$name:$k", value.toString)
                )
              }
          }
        )
      })

      // file systems
      rudderNode.fileSystems.foreach(_.foreach { fs =>
        fs.asObject.foreach(json =>
          json.get("name").flatMap(_.asString).foreach { name =>
            json
              .filterKeys(k => k != "name")
              .fields
              .foreach { (k, v) =>
                v.asSimpleField.foreach(value =>
                  node.getAttributes
                    .put(s"rudder_file_system:$name:$k", value.toString)
                )
              }
          }
        )
      })

      node
    }
  }

  extension (self: Json)
    private def asSimpleField: Either[String, String | Int | Boolean] =
      self
        .as[String]
        .orElse(self.as[Int])
        .orElse(self.as[Boolean])

  extension [A](self: Option[A])
    private def toRequiredFieldError(
        fieldName: String
    ): Option[ErrorMsg] =
      self match
        case Some(value) => None
        case None        =>
          Some(ErrorMsg(s"Required field \"${fieldName}\" is missing"))

}
