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
          case error: Status.Error                 => ???
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
   * Query for groups
   */
  def queryGroups(config: Configuration): Failable[Seq[Group]] = {
    ???
  }

  /**
   * Extract a group from what should be a JSON for group.
   */
  def extractGroup(json: Any) = {
    ???
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

    val rundeckUser =
      config.envVarRundeckUser.getOrElse(config.rundeckDefaultUser)

    val node = NodeEntryImpl()

    node.setNodename(rudderNode.hostname)
    node.setHostname(rudderNode.hostname)
    node.setOsName(rudderNode.os.name)
    node.setOsFamily(rudderNode.os.`type`)
    node.setOsArch(rudderNode.architectureDescription)
    node.setOsVersion(rudderNode.os.version)
    node.setUsername(rundeckUser)
    node.getAttributes.put("rudder_information:id", rudderNode.id)
    node.getAttributes
      .put(
        "rudder_information:node_direct_url",
        config.url.nodeUrl(NodeId(rudderNode.id))
      )
    node.getAttributes
      .put(
        "rudder_information:policy_server_id",
        rudderNode.policyServerId
      )
    node.getAttributes
      .put(
        "rudder_information:last_inventory_date",
        rudderNode.lastInventoryDate
      )
    node.getAttributes
      .put("rudder_information:node_status", rudderNode.status)

    node
  }
}
