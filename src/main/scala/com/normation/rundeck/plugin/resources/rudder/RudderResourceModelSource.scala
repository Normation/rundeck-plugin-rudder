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

import java.util.Properties
import com.dtolabs.rundeck.core.resources.ResourceModelSource
import com.dtolabs.rundeck.core.resources.ResourceModelSourceException
import com.dtolabs.rundeck.core.common.INodeSet
import com.dtolabs.rundeck.core.common.NodeSetImpl

import rapture._
import core._, io._, net._, uri._, json._, codec._

import encodings.`UTF-8`
import jsonBackends.jackson._
import timeSystems.numeric

//Rudder node ID, used as key to synchro nodes
final case class NodeId(value: String)
final case class GroupId(value: String)
final case class RefreshInterval(secondes: Int) {
  val ms = secondes * 1000
}

//Result of a parsed nodes
final case class Node( //hooo, the nice strings!
    id             : NodeId
  , nodename       : String
  , hostname       : String
  , description    : String
  , osFamily       : String
  , osName         : String
  , osArch         : String
  , osVersion      : String
  , remoteUrl      : String
  , rudderGroupTags: List[GroupId]
)

final case class Group(
    id     : GroupId
  , name   : String
  , nodeIds: Set[NodeId]
  , enable : Boolean
  , dynamic: Boolean
)

//Rudder base URL, for ex: https://my.company.com/rudder/
final case class RudderUrl(baseUrl: String) {

  /*
   * We are only compatible with API v6 and above
   * Before v6, we can't have information on all nodes in
   * one request.
   */
  private[this] val v = "6"
  private[this] val url = if(baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.size - 1) else baseUrl

  //utility method
  def nodesApi = s"${url}/api/${v}/nodes"
  def nodeApi(id: NodeId) = nodesApi + "/" + id.value
  def nodeUrl(id: NodeId) =  s"""${url}/secure/nodeManager/searchNodes#{"nodeId":"${id.value}"}"""

  def groupsApi = s"${url}/api/${v}/groups"
  def groupApi(id: GroupId) = groupsApi + "/" + id.value
}

//of course, non value can be null
final case class Configuration(
    url: RudderUrl
  , apiToken: String
  , rundeckUser: String
  , refreshInterval: RefreshInterval //time in ms, should never be < 5000ms

  //add checkSSL certificate
  //rundeck user name from properties
  //ssl port from properties

)



object RudderResourceModelSource {

  def fromProperties(prop: Properties): Failable[RudderResourceModelSource] = {
    import RudderResourceModelSourceFactory._
    def getProp(key: String): Failable[String] = {
      prop.getProperty(key) match {
        case null  => Left(ErrorMsg(s"The property for key '${key}' was not found"))
        case value => Right(value)
      }
    }

    for {
      url     <- getProp(RUDDER_API_ENDPOINT).right
      token   <- getProp(API_TOKEN).right
      user    <- getProp(RUNDECK_USER).right
      refresh <- {
                   val _r =  getProp(REFRESH_INTERVAL).fold(_ => "30", identity)
                   try {
                     Right(RefreshInterval(_r.toInt))
                   } catch {
                     case ex: Exception =>
                       Left(ErrorMsg(s"Error when converting refresh rate to int value: '${}=${}'", Some(ex)))
                   }
                 }.right
    } yield {
      new RudderResourceModelSource(Configuration(RudderUrl(url), token, user, refresh))
    }
  }

}

/**
 * This is the entry point for one Rudder provisionning.
 * It is responsible for the whole querying and mapping of Rudder nodes
 * to Rundeck resources.
 * Here, we have mostly glue. The actual querying and mapping is done in
 * dedicated methods.
 */
class RudderResourceModelSource(configuration: Configuration) extends ResourceModelSource {

  //we are locally caching node instances.
  private[this] var nodes = Map[NodeId, Node]()

  private[this] val mapping = new InstanceToNodeMapper(configuration.rundeckUser, configuration.url.nodeUrl)


  @throws(classOf[ResourceModelSourceException])
  def getNodes(): INodeSet = {

    //for testing, just two nodes
    val json = List("node1", "node2")


    (for {
      nodes <- (Traverse(json) { case node =>
                  mapping.mapNode(node)
                }).right
    } yield {
      nodes.map( mapping.nodeToRundeck )
    }) match {
      case Left(ErrorMsg(m, optEx)) => throw new ResourceModelSourceException(m)
      case Right(nodes) =>
        import scala.collection.JavaConverters._
        val set = new NodeSetImpl()
        set.putNodes(nodes.asJava)
        set
    }
  }

  def queryNodes(): Failable[Seq[Node]] = {
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


    val url = Http.parse(configuration.url.nodesApi)
    val page = url.get(timeout = 5000L, ignoreInvalidCertificates = true, httpHeaders = Map("X-API-Token" -> configuration.apiToken)).slurp[Char]
    val json = Json.parse(page)

    if(json.result.as[String] == "success") {
      Traverse(json.data.nodes.as[Seq[Json]]) { node =>
       extractNode(node)
      }
    } else {
      Left(ErrorMsg("Error when trying to get nodes: " + json.error.as[String]))
    }

  }

  def queryGroups() : Failable[Seq[Group]] = {
    val url = Http.parse(configuration.url.groupsApi)
    val page = url.get(timeout = 5000L, ignoreInvalidCertificates = true, httpHeaders = Map("X-API-Token" -> configuration.apiToken)).slurp[Char]
    val json = Json.parse(page)

    if(json.result.as[String] == "success") {
      Traverse(json.data.groups.as[Seq[Json]]) { group =>
       extractGroup(group)
      }
    } else {
      Left(ErrorMsg("Error when trying to get nodes: " + json.error.as[String]))
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
  private[this] def extractNode(json: Json) = {

    try {

      val id = NodeId(json.id.as[String])
      Right(Node(
          id = id
        , nodename    = id.value
        , hostname    = json.hostname.as[String]
        , description = json.hostname.as[String] + " " + json.os.fullName.as[String]
        , osFamily    = json.os.`type`.as[String]
        , osName      = json.os.name.as[String]
        , osArch      = json.architectureDescription.as[String]
        , osVersion   = json.os.version.as[String]
        , remoteUrl   = "plop"
        , rudderGroupTags = List[GroupId]()
      ))
    } catch {
      case ex: Exception => Left(ErrorMsg("Error when trying to parse node information", Some(ex)))
    }
  }
}

object MainTest {
  def main(args: Array[String]): Unit = {

   val model = new RudderResourceModelSource(Configuration(RudderUrl("https://192.168.46.2/rudder"), "WHFMJwnOl9kxegoDOjUBB7xrunWLqdTe", "rundeck", RefreshInterval(30)))
   val config = Configuration(RudderUrl("https://192.168.46.2/rudder"), "WHFMJwnOl9kxegoDOjUBB7xrunWLqdTe", "rundeck", RefreshInterval(30))


   println(model.queryNodes().fold(identity , x  => x.map( _.hostname)))
   println(model.queryGroups().fold(identity , x  => x.map( _.name)))


  }
}


