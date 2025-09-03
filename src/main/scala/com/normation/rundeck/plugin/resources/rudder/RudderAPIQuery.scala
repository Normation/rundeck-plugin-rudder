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
import scala.concurrent.duration
import scala.concurrent.duration.Duration
import sttp.client4.*
import sttp.client4.httpclient.zio.SttpClient
import sttp.client4.httpclient.zio.send
import sttp.client4.ziojson.*
import sttp.model.Header
import sttp.model.QueryParams
import zio.Chunk
import zio.ZIO
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

  private val logger = RudderLogger(LoggerFactory.getLogger(this.getClass))

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
  ): ZIO[SttpClient, ErrorMsg, Map[NodeId, NodeEntryImpl]] = {

    val request = basicRequest
      .get(
        uri"${config.url.nodesApi}"
          .withParams(QueryParams.fromSeq(Seq((topic, params.mkString(",")))))
      )
      .readTimeout(Duration(config.apiTimeout.ms, duration.MILLISECONDS))
      .headers(Header("X-API-Token", config.apiToken))
      .contentType("application/json")
      .response(asJson[RudderNodeResponse])

    for {
      response <- request.sendApiRequest("nodes")
      json <- response.processApiResponse()
      /* At this point, the result can no longer be an error :
        The Rudder nodes that cannot be imported into Rundeck will produce a warning log.
        All the other viable nodes will be imported as normal.
       */
      map <- ZIO
        .foldLeft(json.data.nodes)(Map.empty[NodeId, NodeEntryImpl])(
          (map, node) =>
            extractNode(node, config)
              .foldZIO(
                err =>
                  logger
                    .warn(
                      s"Error during import of Rudder node with id \'${node.id}\' : ${err.value}"
                        + "\nThis node will not be imported."
                    )
                    .as(map),
                nodeEntry => (map + ((NodeId(node.id), nodeEntry))).succeed
              )
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
          ErrorMsg(
            s"Rudder node with id \'${rudderNode.id}\' is missing one or more required fields : "
          )
            .appendRequiredFieldError(o, "os")
            .appendRequiredFieldError(a, "architectureDescription")
            .appendRequiredFieldError(p, "policyServerId")
            .appendRequiredFieldError(l, "lastInventoryDate")
            .fail

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
      env.foreach((k, v) =>
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

  /**
   * Query for groups
   */
  def queryGroups(
      config: Configuration
  ): ZIO[SttpClient, ErrorMsg, Chunk[Group]] = {

    val request = basicRequest
      .get(uri"${config.url.groupsApi}")
      .readTimeout(Duration(config.apiTimeout.ms, duration.MILLISECONDS))
      .headers(Header("X-API-Token", config.apiToken))
      .contentType("application/json")
      .response(asJson[RudderGroupResponse])

    for {
      response <- request.sendApiRequest("groups")
      json <- response.processApiResponse()
    } yield json.data.groups
  }

  extension (self: Json)
    private def asSimpleField: Either[String, String | Int | Boolean] =
      self
        .as[String]
        .orElse(self.as[Int])
        .orElse(self.as[Boolean])

  extension (self: ErrorMsg)
    private def appendRequiredFieldError[A](
        field: Option[A],
        fieldName: String
    ): ErrorMsg =
      field match
        case Some(_) => self
        case None    =>
          ErrorMsg(
            self.value + "\n" + s"Required field \"${fieldName}\" is missing",
            self.exception
          )

  extension [A](request: Request[Either[ResponseException[String], A]])
    private def sendApiRequest(
        resourceType: String
    ): ZIO[SttpClient, ErrorMsg, Response[
      Either[ResponseException[String], A]
    ]] =
      for {
        response <- send(request).mapError(ex =>
          ErrorMsg(
            s"Error in response for Rudder API ${resourceType} query",
            Some(ex)
          )
        )
      } yield response

  extension [A](response: Response[Either[ResponseException[String], A]])
    private def processApiResponse(): ZIO[SttpClient, ErrorMsg, A] =
      for {
        json <-
          if (response.isSuccess) {
            ZIO
              .fromEither(response.body)
              .mapError(err => ErrorMsg(err.getMessage))
          } else ErrorMsg(response.statusText).fail
      } yield json
}
