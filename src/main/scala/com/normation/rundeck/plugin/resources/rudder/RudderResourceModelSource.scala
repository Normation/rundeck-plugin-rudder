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

import scala.Left
import scala.Right

import com.dtolabs.rundeck.core.common.INodeSet
import com.dtolabs.rundeck.core.common.NodeSetImpl
import com.dtolabs.rundeck.core.resources.ResourceModelSource
import com.dtolabs.rundeck.core.resources.ResourceModelSourceException

import org.apache.log4j.Logger

//import org.apache.commons._
//import org.apache.http._
//import org.apache.http.client._
//import org.apache.http.client.methods.HttpPost
//import org.apache.http.impl.client.DefaultHttpClient
//import org.apache.http.message.BasicNameValuePair
//import org.apache.http.client.entity.UrlEncodedFormEntity
//object HttpPostTester {
//
//  def main(args: Array[String]) {
//
//    val url = "http://localhost:8080/posttest";
//
//    val post = new HttpPost(url)
//    post.addHeader("appid","YahooDemo")
//    post.addHeader("query","umbrella")
//    post.addHeader("results","10")
//
//    val client = new DefaultHttpClient
//    val params = client.getParams
//    params.setParameter("foo", "bar")
//
//    val nameValuePairs = new ArrayList[NameValuePair](1)
//    nameValuePairs.add(new BasicNameValuePair("registrationid", "123456789"));
//    nameValuePairs.add(new BasicNameValuePair("accountType", "GOOGLE"));
//    post.setEntity(new UrlEncodedFormEntity(nameValuePairs));
//
//    // send the post request
//    val response = client.execute(post)
//    println("--- HEADERS ---")
//    response.getAllHeaders.foreach(arg => println(arg))
//
//  }
//
//}

//Rudder node ID, used as key to synchro nodes
final case class NodeId(value: String)
final case class GroupId(value: String)
final case class RefreshInterval(secondes: Int) {
  val ms = secondes * 1000
}

//Result of a parsed nodes
final case class Node(
    id: NodeId
  , nodename: String
  , hostname: String
  , description: String
  , osFamily: String
  , osName: String
  , osArch: String
  , osVersion: String
  , remoteUrl: String
  , rudderGroupTags: List[GroupId]
)

//Rudder base API url, for ex: https://my.company.com/rudder/api/
final case class APIUrl(baseUrl: String) {

  private[this] val url = if(baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.size - 1) else baseUrl

  //utility method
  def nodesUrl = s"${url}/latest/nodes"
  def nodeUrl(id: NodeId) = nodesUrl + "/" + id.value

  def groupsUrl = s"${url}/latest/groupes"
  def groupUrl(id: GroupId) = groupsUrl + "/" + id.value
}

//of course, non value can be null
final case class Configuration(
    apiUrl: APIUrl
  , apiToken: String
  , rundeckUser: String
  , refreshInterval: RefreshInterval //time in ms, should never be < 5000ms
)

final case class ErrorMsg(value: String, exception: Option[Throwable] = None)

object RudderResourceModelSource {

  def fromProperties(prop: Properties): Either[ErrorMsg, RudderResourceModelSource] = {

import RudderResourceModelSourceFactory._
    def getProp(key: String): Either[ErrorMsg, String] = {
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
      new RudderResourceModelSource(Configuration(APIUrl(url), token, user, refresh))
    }
  }

  val logger = Logger.getLogger(RudderResourceModelSource.getClass)

}

/**
 * This is the entry point for one Rudder provisionning.
 * It is responsible for the whole querying and mapping of Rudder nodes
 * to Rundeck resources.
 * Here, we have mostly glue. The actual querying and mapping is done in
 * dedicated methods.
 */
class RudderResourceModelSource(configuration: Configuration) extends ResourceModelSource {

  val logger = Logger.getLogger(this.getClass)

  //we are localy caching node instances.
  private[this] var nodes = Map[NodeId, Node]()

  private[this] val mapping = new InstanceToNodeMapper(configuration.rundeckUser, configuration.apiUrl.nodeUrl)


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
}

/**
 * Traverser of the poor, because no scalaz
 */
object Traverse {
  def apply[T,U](seq: Seq[T])(f: T => Either[ErrorMsg,U]): Either[ErrorMsg,Seq[U]] = {

    //that's clearly not the canonical way of doing it!
    //(simplest way to avoid stack overflow)

    Right(seq.map { x => f(x) match {
        case Right(y) => y
        case Left(msg) => return Left(msg)
    }})
  }
}


