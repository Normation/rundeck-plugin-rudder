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
import com.dtolabs.rundeck.core.plugins.configuration.{
  ConfigurationException,
  Describable,
  Description,
  PropertyUtil
}
import com.dtolabs.rundeck.core.resources.ResourceModelSourceFactory
import com.dtolabs.rundeck.plugins.util.DescriptionBuilder
import org.slf4j.LoggerFactory

/**
 * This file is the entry point for the "RessourceModelSource" plugin for
 * Rundeck.
 *
 * It provides the properties description that will be showed to the user in the
 * plugin details page, and the process to get them from a Properties object.
 */

/**
 * This is the plugin entry point, with the logic to create a new instance of
 * RudderResourceModelSource from a Properties object.
 */
@Plugin(name = "rudder", service = "ResourceModelSource")
class RudderResourceModelSourceFactory(framework: Framework)
    extends ResourceModelSourceFactory
    with Describable {

  private lazy val logger = LoggerFactory.getLogger(this.getClass)

  /**
   * Try to create a new Rudder resource from a set of properties. Report errors
   * to Rundeck by throwing exceptions.
   */
  override def createResourceModelSource(
      properties: Properties
  ): RudderResourceModelSource = {
    RudderResourceModelSourceFactory.configFromProperties(properties) match {
      case Left(ErrorMsg(msg, optex)) =>
        optex match {
          case Some(ex: Exception) => throw new ConfigurationException(msg, ex)
          case _                   => throw new ConfigurationException(msg)
        }

      case Right(config) =>
        logger.info(
          s"Rudder ressource module initialized. Nodes will be fetch at URL ${config.url.nodesApi} " +
            s"with a refresh rate of ${config.refreshInterval.secondes}s"
        )
        new RudderResourceModelSource(config)
    }
  }

  /*
   * Get description - yeah, really ! (useful documentation)
   */
  override def getDescription: Description = {
    RudderResourceModelSourceFactory.DESC
  }

}

/**
 * Here come the actual description of the Rudder plugin properties. All of them
 * are quite self-descriptive, but mainly we have:
 *   - module related properties:
 *     - PROVIDER_NAME
 *   - Rudder API related properties
 *     - URL, authentication token, check ssl certificate, refresh interval
 *   - Rundeck <-> Rudder logic
 *     - what user and ssh port rundeck should use, and where to get them.
 */
object RudderResourceModelSourceFactory {
  import scala.jdk.CollectionConverters._

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

  val DESC = DescriptionBuilder
    .builder()
    .name(PROVIDER_NAME)
    .title("Rudder Resources")
    .description("Produces nodes from Rudder")
    .property(
      PropertyUtil.string(
        RUDDER_BASE_URL,
        "Rudder base URL",
        "The URL to access to your Rudder, for ex.: 'https://my.company.com/rudder/'",
        true,
        null
      )
    )
    .property(
      PropertyUtil.select(
        API_VERSION,
        "API version",
        "The API version to use for rundeck. You should use 'latest' appart for compat with older Rudder up to 7.x where version should be '12'",
        true,
        "latest",
        Seq("latest", "12").asJava
      )
    )
    .property(
      PropertyUtil.string(
        API_TOKEN,
        "API token",
        "The API token to use for rundeck, defined in Rudder API administration page",
        true,
        null
      )
    )
    .property(
      PropertyUtil.integer(
        API_TIMEOUT,
        "API timeout",
        "Maximum time in seconds to wait for an answer from Rudder API (default is 5s)",
        true,
        "5"
      )
    )
    .property(
      PropertyUtil.bool(
        API_CHECK_CERTIFICATE,
        "Check certificate",
        "If true, SSL certificate for Rudder API will be check (and in particular, self-signed certificated will be refused) (default is true)",
        true,
        "true"
      )
    )
    .property(
      PropertyUtil.integer(
        REFRESH_INTERVAL,
        "Refresh Interval",
        "Minimum time in seconds between API requests to Rudder (default is 30)",
        true,
        "30"
      )
    )
    .property(
      PropertyUtil.string(
        DEFAULT_RUNDECK_USER,
        "Rundeck user",
        "The user used by rundeck to connect to nodes",
        true,
        "rundeck"
      )
    )
    .property(
      PropertyUtil.string(
        ENV_VARIABLE_RUNDECK_USER,
        "Environment variable for rundeck user",
        "If not empty, look for that environment variable on the node and if defined, use its value in place of default rundeck user configured above",
        false,
        null
      )
    )
    .property(
      PropertyUtil.integer(
        DEFAULT_SSH_PORT,
        "Default SSH port",
        "The default SSH port used by rundeck to connect to nodes",
        true,
        "22"
      )
    )
    .property(
      PropertyUtil.string(
        ENV_VARIABLE_SSH_PORT,
        "Environment variable for SSH port",
        "If not empty, look for that environment variable on the node and if defined, use its value in place of default SSH port",
        false,
        null
      )
    )
    .build();

  /**
   * The parsing logic for the properties file. Nothing really interesting, just
   * checking if things exist, if they are defined, if there value make sense,
   * etc.
   */
  def configFromProperties(prop: Properties): Failable[Configuration] = {
    def getTProp[T](key: String, trans: String => T): Failable[T] = {
      prop.getProperty(key) match {
        case null  =>
          Left(
            ErrorMsg(s"The property for mandatory key '${key}' was not found")
          )
        case value =>
          try {
            Right(trans(value))
          } catch {
            case ex: Exception =>
              Left(
                ErrorMsg(s"Error when converting ${key}: '${value}'", Some(ex))
              )
          }
      }
    }
    def getProp(key: String): Failable[String] = getTProp(key, identity)

    for {
      url <- getProp(RUDDER_BASE_URL)
      token <- getProp(API_TOKEN)
      user <- getProp(DEFAULT_RUNDECK_USER)
      timeout <- getTProp(API_TIMEOUT, Integer.parseInt)
      checkSSL <- getTProp(API_CHECK_CERTIFICATE, _.toBoolean)
      sshPort <- getTProp(DEFAULT_SSH_PORT, _.toInt)
      refresh <- getTProp(REFRESH_INTERVAL, _.toInt)
      apiVersion <- getProp(API_VERSION).fold(
        Left(_),
        {
          case "12"      => Right(ApiV12)
          case "latest"  => Right(ApiLatest)
          case x: String =>
            Left(
              ErrorMsg(
                s"The API version '${x}' is not authorized, only accepting '12' or 'latest'"
              )
            )
        }
      )
    } yield {
      val envVarSSLPort =
        getProp(ENV_VARIABLE_SSH_PORT).fold(_ => None, x => Some(x))
      val envVarUser =
        getProp(ENV_VARIABLE_RUNDECK_USER).fold(_ => None, x => Some(x))

      // yeah, we have a nice, curated configuration object now!
      Configuration(
        RudderUrl(url, apiVersion),
        token,
        TimeoutInterval(timeout),
        checkSSL,
        TimeoutInterval(refresh),
        sshPort,
        envVarSSLPort,
        user,
        envVarUser
      )
    }
  }
}
