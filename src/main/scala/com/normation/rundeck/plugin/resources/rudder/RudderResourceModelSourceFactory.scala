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

package com.normation.rundeck.plugin.resources.rudder;

import java.util.Properties

import com.dtolabs.rundeck.core.common.Framework
import com.dtolabs.rundeck.core.plugins.Plugin
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException
import com.dtolabs.rundeck.core.plugins.configuration.Describable
import com.dtolabs.rundeck.core.plugins.configuration.PropertyUtil
import com.dtolabs.rundeck.core.resources.ResourceModelSourceFactory
import com.dtolabs.rundeck.plugins.util.DescriptionBuilder


/**
 * This file provides the property description that will be showed to the
 * user in the plugin details page.
 */
@Plugin(name = "rudder", service = "ResourceModelSource")
class RudderResourceModelSourceFactory(framework: Framework) extends ResourceModelSourceFactory with Describable with Loggable {
  override def createResourceModelSource(properties: Properties) = {
    RudderResourceModelSource.fromProperties(properties) match {
      case Left(ErrorMsg(msg, optex)) =>
        optex match {
          case Some(ex: Exception) => throw new ConfigurationException(msg, ex)
          case _                   => throw new ConfigurationException(msg)
        }

      case Right(x) =>
        logger.info(s"Rudder ressource module initialized. Nodes will be fetch at URL ${x.configuration.url.nodesApi} with a refresh rate of ${x.configuration.refreshInterval.secondes}s")
        x
    }
  }

  override def getDescription() = {
    RudderResourceModelSourceFactory.DESC
  }
}

/**
 * Here come the actual description of the Rudder plugin properties
 */
object RudderResourceModelSourceFactory {

  val PROVIDER_NAME = "rudder"
  val API_TOKEN = "apiToken"
  val API_VERSION = "apiVersion"
  val API_TIMEOUT = "apiTimeout"
  val API_CHECK_CERTIFICATE = "apiCheckCertificate"
  val RUDDER_API_ENDPOINT = "rudderAPIUrl"
  val REFRESH_INTERVAL = "refreshInterval"
  val RUNDECK_USER = "rundeckUser"
  val SSH_PORT = "sshPort"
  val ENV_VARIABLE_RUNDECK_USER = "envVarRundeckUser"
  val ENV_VARIABLE_SSL_PORT = "envVarSSLPort"

  import scala.collection.JavaConverters._

  /*
   * All the properties are defined here
   */

  val DESC = DescriptionBuilder.builder()
    .name(PROVIDER_NAME)
    .title("Rudder Resources")
    .description("Produces nodes from Rudder")

    .property(PropertyUtil.string(RUDDER_API_ENDPOINT, "Rudder base URL"
      , "The URL to access to your Rudder, for ex.: 'https://my.company.com/rudder/'", true, null))
    .property(PropertyUtil.select(API_VERSION, "API version"
      , "The API version to use for rundeck. For Rudder 2.11 or 3.0, use '4', for more recent version use '6'", true
      , "latest", Seq("4", "6").asJava))
    .property(PropertyUtil.string(API_TOKEN, "API token"
      , "The API token to use for rundeck, defined in Rudder API administration page", true, null))
    .property(PropertyUtil.integer(API_TIMEOUT, "API timeout"
      , "Maximum time to wait for an answer from Rudder API (default is 5s)", true, "5"))
    .property(PropertyUtil.bool(API_CHECK_CERTIFICATE, "Check certificate"
      , "If true, SSL certificate for Rudder API will be check (and in particular, self-signed certificated will be refused", true, "true"))
    .property(PropertyUtil.integer(REFRESH_INTERVAL, "Refresh Interval"
      , "Minimum time in seconds between API requests to AWS (default is 30)", true, "30"))
    .property(PropertyUtil.string(RUNDECK_USER, "Rundeck user"
      , "The user used by rundeck to connect to nodes", true, "rundeck"))
    .property(PropertyUtil.string(ENV_VARIABLE_RUNDECK_USER, "Environment variable for rundeck user"
      , "If not empty, look for that environment variable on the node and use its value in place of default rundeck user configured above", false, null))
    .property(PropertyUtil.integer(SSH_PORT, "Default SSH port"
      , "The default SSH port used by rundeck to connect to nodes", true, "22"))
    .property(PropertyUtil.string(ENV_VARIABLE_SSL_PORT, "Environment variable for ssl port"
      , "If not empty, look for that environment variable on the node and use its value in place of default SSL port", false, null))
    .build();
}

