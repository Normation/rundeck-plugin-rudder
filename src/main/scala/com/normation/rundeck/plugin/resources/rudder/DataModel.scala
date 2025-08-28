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

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.DeriveSchema
import zio.schema.Schema
import zio.schema.derived

/**
 * This file contains data structure definition for our model.
 *
 * There is mainly:
 *   - things related to the configuration of the module
 *   - what is a node / a group
 *   - our error container class
 */

//////////////////////////////// Configuration of the plugin ////////////////////////////////

sealed trait ApiVersion { def value: String }

/*
 * If you use latest with Rudder 4.x, you
 * will have a clear error, nothing works => change version.
 * Auto API upgrade are not relevant, since the plugin won't
 * take advantage of them without an update.
 */
case object ApiLatest extends ApiVersion { val value = "latest" }

/*
 * Rudder base URL, for ex: https://my.company.com/rudder/
 * We are adding utility methods to directly get the
 * nodes/groups api URL from it.
 */
final case class RudderUrl(baseUrl: String, version: ApiVersion) {

  private val url =
    if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1)
    else baseUrl

  // endpoint for groups API (only need all of them)
  def groupsApi = s"${url}/api/latest/groups"

  // endpoint for nodes API - all of them, or just one
  def nodesApi = s"${url}/api/${version.value}/nodes"
  def nodeApi(id: NodeId): String = nodesApi + "/" + id

  // node details on Rudder web UI
  def nodeUrl(id: NodeId) =
    s"""${url}/secure/nodeManager/searchNodes#{"nodeId":"${id}"}"""
}

final case class TimeoutInterval(seconds: Int) {
  val ms: Long = seconds * 1000L
}
object TimeoutInterval {
  given Conversion[TimeoutInterval, zio.Duration] = _.seconds.seconds
}

/*
 * Our plugin configuration container, where
 * all the relevant information are stored in
 * a way that we have only meaningful values.
 *
 * Of course, values can't be null here
 */
final case class Configuration(
    url: RudderUrl,
    apiToken: String,
    apiTimeout: TimeoutInterval,
    checkCertificate: Boolean,
    refreshInterval: TimeoutInterval, // should never be < 5000ms
    sshDefaultPort: Int,
    envVarSSLPort: Option[String],
    rundeckDefaultUser: String,
    envVarRundeckUser: Option[String]
)

//////////////////////////////// Nodes and Groups ////////////////////////////////

//Rudder node ID, used as key to find which nodes belong a given group
opaque type NodeId = String
object NodeId {
  def apply(string: String): NodeId = string
  given decoder: JsonDecoder[NodeId] = JsonDecoder.string
  given schema: Schema[NodeId] = Schema.primitive[String]
}

case class NodeData(nodes: Chunk[Node]) derives JsonDecoder, Schema

case class Node(
    id: String,
    hostname: String,
    status: String,
    architectureDescription: Option[String],
    ipAddresses: Chunk[String],
    lastInventoryDate: Option[String],
    os: Option[Os],
    policyServerId: Option[String],
    properties: Chunk[Property],
    ram: Option[Int],
    accounts: Option[Chunk[String]],
    environmentVariables: Option[Map[String, String]],
    networkInterfaces: Option[Chunk[Json]],
    storage: Option[Chunk[Json]],
    fileSystems: Option[Chunk[Json]]
) derives JsonDecoder,
      Schema

case class Property(name: String, value: String) derives JsonDecoder

case class Os(
    `type`: String,
    name: String,
    version: String,
    fullName: String,
    kernelVersion: String
) derives JsonDecoder

case class RudderNodeResponse(
    action: String,
    result: String,
    data: NodeData
) derives JsonDecoder,
      Schema

//notice: for nodes, we directly use rundeck
//NodeEntryImpl, interfacing is much easier.

// definition of a group, with a type for its id
opaque type GroupId = String
object GroupId {
  def apply(string: String): GroupId = string
  given decoder: JsonDecoder[GroupId] = JsonDecoder.string
  given schema: Schema[GroupId] = Schema.primitive[String]
}

case class RudderGroupResponse(
    action: String,
    result: String,
    data: GroupData
) derives JsonDecoder,
      Schema

case class GroupData(groups: Chunk[Group]) derives JsonDecoder, Schema

final case class Group(
    id: GroupId,
    displayName: String,
    nodeIds: Set[NodeId],
    enabled: Boolean,
    dynamic: Boolean
) derives JsonDecoder,
      Schema

//////////////////////////////// Error container ////////////////////////////////

/**
 * ErrorMsg is a container for an Error with a human-readable message, and
 * optionally the root exception that caused the error.
 */

final case class ErrorMsg(value: String, exception: Option[Throwable] = None)
