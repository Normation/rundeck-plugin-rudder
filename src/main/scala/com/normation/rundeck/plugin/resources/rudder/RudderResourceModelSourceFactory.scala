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
import org.apache.log4j.Logger


/**
 * This file is the entry point for the "RessourceModelSource" plugin
 * for Rundeck.
 *
 * It provides the properties description that will be showed to the
 * user in the plugin details page, and the process to get them from
 * a Properties object.
 */



/**
 * This is the plugin entry point, with the logic to create a
 * new instance of RudderResourceModelSource from a Properties object.
 */
@Plugin(name = "rudder", service = "ResourceModelSource")
class RudderResourceModelSourceFactory(framework: Framework) extends ResourceModelSourceFactory with Describable {

  lazy val logger = Logger.getLogger(this.getClass)

  override def createResourceModelSource(properties: Properties) = {
    RudderResourceModelSourceFactory.configFromProperties(properties) match {
      case Left(ErrorMsg(msg, optex)) =>
        optex match {
          case Some(ex: Exception) => throw new ConfigurationException(msg, ex)
          case _                   => throw new ConfigurationException(msg)
        }

      case Right(config) =>
        logger.info(
          s"Rudder ressource module initialized. Nodes will be fetch at URL ${config.url.nodesApi} "+
          s"with a refresh rate of ${config.refreshInterval.secondes}s"
        )
        new RudderResourceModelSource(config)
    }
  }

  override def getDescription() = {
    RudderResourceModelSourceFactory.DESC
  }

}



/**
 * Here come the actual description of the Rudder plugin properties.
 * All of them are quite self-descriptive, but mainly we have:
 * - module related properties:
 * 	 - PROVIDER_NAME
 * - Rudder API related properties
 *   - URL, authentication token, check ssl certificate, refresh interval
 * - Rundeck <-> Rudder logic
 *   - what user and ssh port rundeck should use, and where to get them.
 */
object RudderResourceModelSourceFactory {
  import scala.collection.JavaConverters._

  val PROVIDER_NAME = "rudder"

  val RUDDER_BASE_URL = "rudderUrl"

  val API_TOKEN = "apiToken"
  val API_VERSION = "apiVersion"
  val API_TIMEOUT = "apiTimeout"
  val API_CHECK_CERTIFICATE = "apiCheckCertificate"

  val REFRESH_INTERVAL = "refreshInterval"

  val DEFAULT_RUNDECK_USER = "defaultRundeckUser"
  val DEFAULT_SSH_PORT = "defaultSshPort"
  val ENV_VARIABLE_RUNDECK_USER = "envVarRundeckUser"
  val ENV_VARIABLE_SSH_PORT = "envVarSshPort"


  val DESC = DescriptionBuilder.builder()
    .name(PROVIDER_NAME)
    .title("Rudder Resources")
    .description("Produces nodes from Rudder")

    .property(PropertyUtil.string(RUDDER_BASE_URL, "Rudder base URL"
      , "The URL to access to your Rudder, for ex.: 'https://my.company.com/rudder/'", true, null))
    .property(PropertyUtil.select(API_VERSION, "API version"
      , "The API version to use for rundeck. For Rudder 2.11 or 3.0, use '4', for more recent version use '6'", true
      , "latest", Seq("4", "6").asJava))
    .property(PropertyUtil.string(API_TOKEN, "API token"
      , "The API token to use for rundeck, defined in Rudder API administration page", true, null))
    .property(PropertyUtil.integer(API_TIMEOUT, "API timeout"
      , "Maximum time in seconds to wait for an answer from Rudder API (default is 5s)", true, "5"))
    .property(PropertyUtil.bool(API_CHECK_CERTIFICATE, "Check certificate"
      , "If true, SSL certificate for Rudder API will be check (and in particular, self-signed certificated will be refused) (default is true)", true, "true"))
    .property(PropertyUtil.integer(REFRESH_INTERVAL, "Refresh Interval"
      , "Minimum time in seconds between API requests to Rudder (default is 30)", true, "30"))
    .property(PropertyUtil.string(DEFAULT_RUNDECK_USER, "Rundeck user"
      , "The user used by rundeck to connect to nodes", true, "rundeck"))
    .property(PropertyUtil.string(ENV_VARIABLE_RUNDECK_USER, "Environment variable for rundeck user"
      , "If not empty, look for that environment variable on the node and if defined, use its value in place of default rundeck user configured above", false, null))
    .property(PropertyUtil.integer(DEFAULT_SSH_PORT, "Default SSH port"
      , "The default SSH port used by rundeck to connect to nodes", true, "22"))
    .property(PropertyUtil.string(ENV_VARIABLE_SSH_PORT, "Environment variable for SSH port"
      , "If not empty, look for that environment variable on the node and if defined, use its value in place of default SSH port", false, null))
    .build();


  /**
   * The parsing logic for the properties file.
   * Nothing really interesting, just checking if
   * things exist, if they are defined, if there
   * value make sense, etc.
   */
  def configFromProperties(prop: Properties): Failable[Configuration] = {
    def getProp(key: String): Failable[String] = {
      prop.getProperty(key) match {
        case null  => Left(ErrorMsg(s"The property for mandatory key '${key}' was not found"))
        case value => Right(value)
      }
    }
    def getIntProp(key: String) = getProp(key) match {
      case Left(x) => Left(x)
      case Right(x) => try {
                         Right(x.toInt)
                       } catch { case ex: Exception =>
                         Left(ErrorMsg(s"Error when converting ${key} to int value: '${x}'", Some(ex)))
                       }
    }
    def getBoolProp(key: String) = getProp(key) match {
      case Left(x) => Left(x)
      case Right(x) => try {
                         Right(x.toBoolean)
                       } catch { case ex: Exception =>
                         Left(ErrorMsg(s"Error when converting ${key} to boolean value: '${x}'", Some(ex)))
                       }
    }

    for {
      url        <- getProp(RUDDER_BASE_URL).right
      token      <- getProp(API_TOKEN).right
      user       <- getProp(DEFAULT_RUNDECK_USER).right
      timeout    <- getIntProp(API_TIMEOUT).right
      checkSSL   <- getBoolProp(API_CHECK_CERTIFICATE).right
      sshPort    <- getIntProp(DEFAULT_SSH_PORT).right
      refresh    <- getIntProp(REFRESH_INTERVAL).right
      apiVersion <- getProp(API_VERSION).fold(
                      Left(_)
                    , x => x match {
                        case "4" => Right(ApiV4)
                        case "6" => Right(ApiV6)
                        case _ => Left(ErrorMsg(s"The API version '${x}' is not authorized, only accepting '4' and '6'"))
                    }).right
    } yield {
      val envVarSSLPort = getProp(ENV_VARIABLE_SSH_PORT).fold(_ => None, x => Some(x))
      val envVarUser = getProp(ENV_VARIABLE_RUNDECK_USER).fold(_ => None, x => Some(x))

      Configuration(
          RudderUrl(url, apiVersion), token, TimeoutInterval(timeout), checkSSL, TimeoutInterval(refresh)
        , sshPort, envVarSSLPort, user, envVarUser
      )
    }
  }
}
