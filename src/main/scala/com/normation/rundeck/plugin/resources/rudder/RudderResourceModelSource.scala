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

import com.dtolabs.rundeck.core.common._
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException
import com.dtolabs.rundeck.core.resources.ResourceModelSource
import com.dtolabs.rundeck.core.resources.ResourceModelSourceException
import org.apache.log4j.Logger
import java.io._
import org.apache.commons._
import org.apache.http._
import org.apache.http.client._
import org.apache.http.client.methods.HttpPost
import org.apache.http.impl.client.DefaultHttpClient
import java.util.ArrayList
import org.apache.http.message.BasicNameValuePair
import org.apache.http.client.entity.UrlEncodedFormEntity
import scala.concurrent.impl.Future
import java.util.Properties

object HttpPostTester {

  def main(args: Array[String]) {

    val url = "http://localhost:8080/posttest";

    val post = new HttpPost(url)
    post.addHeader("appid","YahooDemo")
    post.addHeader("query","umbrella")
    post.addHeader("results","10")

    val client = new DefaultHttpClient
    val params = client.getParams
    params.setParameter("foo", "bar")

    val nameValuePairs = new ArrayList[NameValuePair](1)
    nameValuePairs.add(new BasicNameValuePair("registrationid", "123456789"));
    nameValuePairs.add(new BasicNameValuePair("accountType", "GOOGLE"));
    post.setEntity(new UrlEncodedFormEntity(nameValuePairs));

    // send the post request
    val response = client.execute(post)
    println("--- HEADERS ---")
    response.getAllHeaders.foreach(arg => println(arg))

  }

}

//Rudder node ID, used as key to synchro nodes
final case class NodeId(value: String)
final case class GroupId(value: String)

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

//of course, non value can be null
final case class Configuration(
    apiUrl: String
  , apiToken: String
  , rundeckUser: String
  , refreshInterval: Int //time in ms, should never be < 5000ms
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
                     Right(_r.toInt)
                   } catch {
                     case ex: Exception =>
                       Left(ErrorMsg(s"Error when converting refresh rate to int value: '${}=${}'", Some(ex)))
                   }
                 }.right
    } yield {
      new RudderResourceModelSource(Configuration(url, token, user, refresh * 1000))
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

  val logger = Logger.getLogger(this.getClass)

  //we are localy caching node instances.
  private[this] var nodes = Map[NodeId, Node]()




    public EC2ResourceModelSource(final Properties configuration) {
        this.accessKey = configuration.getProperty(EC2ResourceModelSourceFactory.ACCESS_KEY);
        this.secretKey = configuration.getProperty(EC2ResourceModelSourceFactory.SECRET_KEY);
        this.endpoint = configuration.getProperty(EC2ResourceModelSourceFactory.ENDPOINT);
        this.httpProxyHost = configuration.getProperty(EC2ResourceModelSourceFactory.HTTP_PROXY_HOST);
        int proxyPort = 80;

        final String proxyPortStr = configuration.getProperty(EC2ResourceModelSourceFactory.HTTP_PROXY_PORT);
        if (null != proxyPortStr && !"".equals(proxyPortStr)) {
            try {
                proxyPort = Integer.parseInt(proxyPortStr);
            } catch (NumberFormatException e) {
                logger.warn(EC2ResourceModelSourceFactory.HTTP_PROXY_PORT + " value is not valid: " + proxyPortStr);
            }
        }
        this.httpProxyPort = proxyPort;
        this.httpProxyUser = configuration.getProperty(EC2ResourceModelSourceFactory.HTTP_PROXY_USER);
        this.httpProxyPass = configuration.getProperty(EC2ResourceModelSourceFactory.HTTP_PROXY_PASS);

        this.filterParams = configuration.getProperty(EC2ResourceModelSourceFactory.FILTER_PARAMS);
        this.mappingParams = configuration.getProperty(EC2ResourceModelSourceFactory.MAPPING_PARAMS);
        final String mappingFilePath = configuration.getProperty(EC2ResourceModelSourceFactory.MAPPING_FILE);
        if (null != mappingFilePath) {
            mappingFile = new File(mappingFilePath);
        }
        int refreshSecs = 30;
        final String refreshStr = configuration.getProperty(EC2ResourceModelSourceFactory.REFRESH_INTERVAL);
        if (null != refreshStr && !"".equals(refreshStr)) {
            try {
                refreshSecs = Integer.parseInt(refreshStr);
            } catch (NumberFormatException e) {
                logger.warn(EC2ResourceModelSourceFactory.REFRESH_INTERVAL + " value is not valid: " + refreshStr);
            }
        }
        refreshInterval = refreshSecs * 1000;
        if (configuration.containsKey(EC2ResourceModelSourceFactory.USE_DEFAULT_MAPPING)) {
            useDefaultMapping = Boolean.parseBoolean(configuration.getProperty(
                EC2ResourceModelSourceFactory.USE_DEFAULT_MAPPING));
        }
        if (configuration.containsKey(EC2ResourceModelSourceFactory.RUNNING_ONLY)) {
            runningOnly = Boolean.parseBoolean(configuration.getProperty(
                EC2ResourceModelSourceFactory.RUNNING_ONLY));
        }
        if (null != accessKey && null != secretKey) {
            credentials = new BasicAWSCredentials(accessKey.trim(), secretKey.trim());
        }

        if (null != httpProxyHost && !"".equals(httpProxyHost)) {
            clientConfiguration.setProxyHost(httpProxyHost);
            clientConfiguration.setProxyPort(httpProxyPort);
            clientConfiguration.setProxyUsername(httpProxyUser);
            clientConfiguration.setProxyPassword(httpProxyPass);
        }

        initialize();
    }

    private void initialize() {
        final ArrayList<String> params = new ArrayList<String>();
        if (null != filterParams) {
            Collections.addAll(params, filterParams.split(";"));
        }
        loadMapping();
        mapper = new InstanceToNodeMapper(credentials, mapping, clientConfiguration);
        mapper.setFilterParams(params);
        mapper.setEndpoint(endpoint);
        mapper.setRunningStateOnly(runningOnly);
    }


    public synchronized INodeSet getNodes() throws ResourceModelSourceException {
        checkFuture();
        if (!needsRefresh()) {
            if (null != iNodeSet) {
                logger.info("Returning " + iNodeSet.getNodeNames().size() + " cached nodes from EC2");
            }
            return iNodeSet;
        }
        if (lastRefresh > 0 && queryAsync && null == futureResult) {
            futureResult = mapper.performQueryAsync();
            lastRefresh = System.currentTimeMillis();
        } else if (!queryAsync || lastRefresh < 1) {
            //always perform synchronous query the first time
            iNodeSet = mapper.performQuery();
            lastRefresh = System.currentTimeMillis();
        }
        if (null != iNodeSet) {
            logger.info("Read " + iNodeSet.getNodeNames().size() + " nodes from EC2");
        }
        return iNodeSet;
    }

    /**
     * if any future results are pending, check if they are done and retrieve the results
     */
    private void checkFuture() {
        if (null != futureResult && futureResult.isDone()) {
            try {
                iNodeSet = futureResult.get();
            } catch (InterruptedException e) {
                logger.debug(e);
            } catch (ExecutionException e) {
                logger.warn("Error performing query: " + e.getMessage(), e);
            }
            futureResult = null;
        }
    }

    /**
     * Returns true if the last refresh time was longer ago than the refresh interval
     */
    private boolean needsRefresh() {
        return refreshInterval < 0 || (System.currentTimeMillis() - lastRefresh > refreshInterval);
    }


}
