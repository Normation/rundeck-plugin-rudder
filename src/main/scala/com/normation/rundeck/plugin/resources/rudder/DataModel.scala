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

import zio.json.*
import zio.schema.{DeriveSchema, Schema}

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
 * Rationnal to not use "latest" in place of 12.
 * If you use latest with Rudder 4.x, you
 * will have a clear error, nothing works => change version.
 * Auto API upgrade are not relevant, since the plugin won't
 * take advantage of them without an update.
 */
case object ApiV12 extends ApiVersion { val value = "12" }
case object ApiLatest extends ApiVersion { val value = "latest" }

/*
 * Rudder base URL, for ex: https://my.company.com/rudder/
 * We are adding utility methods to directly get the
 * nodes/groups api URL from it.
 */
final case class RudderUrl(baseUrl: String, version: ApiVersion) {

  private val url = {
    if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1)
    else baseUrl
  }

  // endpoint for groups API (only need all of them)
  def groupsApi = s"${url}/api/latest/groups"

  // endpoint for nodes API - all of them, or just one
  def nodesApi = s"${url}/api/${version.value}/nodes"
  def nodeApi(id: NodeId): String = nodesApi + "/" + id.value

  // node details on Rudder web UI
  def nodeUrl(id: NodeId) =
    s"""${url}/secure/nodeManager/searchNodes#{"nodeId":"${id.value}"}"""
}

/*
 * Simple class for duration, with a ms variant !
 */
final case class TimeoutInterval(secondes: Int) {
  val ms = secondes * 1000L
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
    refreshInterval: TimeoutInterval, // time in ms, should never be < 5000ms
    sshDefaultPort: Int,
    envVarSSLPort: Option[String],
    rundeckDefaultUser: String,
    envVarRundeckUser: Option[String]
)

//////////////////////////////// Nodes and Groups ////////////////////////////////

//Rudder node ID, used as key to synchro nodes
final case class NodeId(value: String)

case class Data(
    nodes: Seq[Node]
)

object Data {
  given JsonDecoder[Data] = DeriveJsonDecoder.gen
}

case class Machine(
    id: String,
    `type`: String,
    manufacturer: Option[String],
    serialNumber: Option[String]
)

object Machine {
  given JsonDecoder[Machine] = DeriveJsonDecoder.gen
}

case class ManagementTechnology(
    name: String,
    version: Option[String],
    capabilities: Seq[String],
    nodeKind: String
)

object ManagementTechnology {
  given JsonDecoder[ManagementTechnology] = DeriveJsonDecoder.gen
}

case class Node(
    id: String,
    hostname: String,
    status: String,
    architectureDescription: String, // optional in the Rudder API, but required here
    description: Option[String],
    ipAddresses: Seq[String],
    acceptanceDate: Option[String],
    lastInventoryDate: String, // optional in the Rudder API, but required here
    machine: Option[Machine],
    os: Os, // optional in the Rudder API, but required here
    policyServerId: String, // optional in the Rudder API, but required here
    properties: Seq[Property],
    policyMode: Option[String],
    ram: Option[Int],
    timezone: Option[Timezone],
    accounts: Option[Seq[String]],
    environmentVariables: Option[Seq[EnvironmentVariable]],
    networkInterfaces: Option[Seq[NetworkInterface]],
    storage: Option[Seq[Disk]],
    fileSystems: Option[Seq[FileSystem]]
)

object Node {
  given JsonDecoder[Node] = DeriveJsonDecoder.gen
}

case class Property(name: String, value: String)
object Property {
  given JsonDecoder[Property] = DeriveJsonDecoder.gen
}

case class Os(
    `type`: String,
    name: String,
    version: String,
    fullName: String,
    kernelVersion: String
)

object Os {
  given JsonDecoder[Os] = DeriveJsonDecoder.gen
}

case class RudderNodeResponse(
    action: String,
    result: String,
    data: Data
)
object RudderNodeResponse {
  given JsonDecoder[RudderNodeResponse] = DeriveJsonDecoder.gen
}

case class Timezone(
    name: String,
    offset: String
)

object Timezone {
  given JsonDecoder[Timezone] = DeriveJsonDecoder.gen
}

case class EnvironmentVariable(
    name: String,
    value: String
)
object EnvironmentVariable {
  given JsonDecoder[EnvironmentVariable] = DeriveJsonDecoder.gen
}

case class NetworkInterface(
    name: Option[String],
    mask: Option[Seq[String]],
    `type`: Option[String],
    speed: Option[String],
    status: Option[String],
    dhcpServer: Option[String],
    macAddress: Option[String],
    ipAddresses: Option[Seq[String]]
)
object NetworkInterface {
  given JsonDecoder[NetworkInterface] = DeriveJsonDecoder.gen
}

case class Disk(
    name: String,
    `type`: Option[String],
    `size`: Option[Int],
    model: Option[String],
    firmware: Option[String],
    quantity: Int,
    description: Option[String],
    manufacturer: Option[String],
    serialNumber: Option[String]
)
object Disk {
  given JsonDecoder[Disk] = DeriveJsonDecoder.gen
}

case class FileSystem(
    name: Option[String],
    mountPoint: String,
    description: Option[String],
    fileCount: Option[Int],
    freeSpace: Option[Int],
    totalSpace: Option[Int]
)
object FileSystem {
  given JsonDecoder[FileSystem] = DeriveJsonDecoder.gen
}

//notice: for nodes, we directly use rundeck
//NodeEntryImpl, interfacing is much easier.

// definition of a group, with a type
// for its id
final case class GroupId(value: String)

final case class Group(
    id: GroupId,
    name: String,
    nodeIds: Set[NodeId],
    enable: Boolean,
    dynamic: Boolean
)

//////////////////////////////// Error container ////////////////////////////////

/**
 * ErrorMsg is a container for an Error with a human-readable message, and
 * optionally the root exception that caused the error.
 */

final case class ErrorMsg(value: String, exception: Option[Throwable] = None)
