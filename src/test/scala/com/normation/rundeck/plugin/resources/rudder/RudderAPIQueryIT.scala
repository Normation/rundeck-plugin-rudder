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

import zio.Duration
import zio.Scope
import zio.ZIO
import zio.ZIOAppArgs
import zio.ZIOAppDefault
import zio.ZLayer
import zio.http.Client
import zio.http.ClientSSLConfig
import zio.http.DnsResolver
import zio.http.ZClient
import zio.http.netty.NettyConfig

class RudderAPIQueryIT

object RudderAPIQueryIT extends ZIOAppDefault {

  def program(config: Configuration) =
    for {
      _ <- RudderAPIQuery
        .queryNodes(config)
        .debug
      groups <- RudderAPIQuery
        .queryGroups(config)
        .debug
    } yield ()

  override def run: ZIO[Any & ZIOAppArgs & Scope, Any, Any] =

    val clientConfig = ZClient.Config.default
      .connectionTimeout(
        Duration.fromSeconds(5)
      )
      .ssl(ClientSSLConfig.FromJavaxNetSsl())

    // client defaults, see https://github.com/zio/zio-http/issues/2403
    val nettyConfig = NettyConfig.defaultWithFastShutdown

    for {
      args <- getArgs
      (apiToken, rudderUrl) <-
        (args.headOption, args.drop(1).headOption) match
          case (Some(apiToken), Some(rudderUrl)) =>
            (apiToken, rudderUrl).succeed
          case _                                 =>
            "Please provide an API token and a rudder url as program arguments.".fail

      config = Configuration(
        url = RudderUrl(rudderUrl, ApiLatest),
        apiToken = apiToken,
        apiTimeout = TimeoutInterval(0),
        checkCertificate = true,
        refreshInterval = TimeoutInterval(0),
        sshDefaultPort = 8080,
        envVarSSLPort = None,
        rundeckDefaultUser = "rundeck",
        envVarRundeckUser = None
      )

      _ <- program(config).provide(
        ZLayer.succeed(clientConfig),
        ZLayer.succeed(nettyConfig),
        Client.live.orDie,
        DnsResolver.default
      )
    } yield ()

}
