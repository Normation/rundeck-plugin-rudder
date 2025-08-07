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
import zio.http.Header.ContentTransferEncoding.XToken
import zio.{Chunk, ZIO}
import zio.http.{Client, Headers, QueryParams, Request, URL}
import zio.http.Method.GET

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

    ZIO
      .scoped {
        Client
          .streaming(
            Request(
              method = GET,
              url = fullUrl,
              headers = Headers.fromIterable(Iterable(XToken(config.apiToken)))
            )
          )
          .flatMap(_.body.asString)
      }
      .mapError(ex => {
        ErrorMsg(
          s"Error when trying to get node(s) at url ${fullUrl.encode}: " + ex.getMessage,
          Some(ex)
        )
      })
      .map(body =>
        Map.apply(NodeId("tmp_id") -> NodeEntryImpl("tmp.node.name"))
      )

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
      json: Any,
      config: Configuration
  ): Failable[(NodeId, NodeEntryImpl)] = {
    ???
  }
}
