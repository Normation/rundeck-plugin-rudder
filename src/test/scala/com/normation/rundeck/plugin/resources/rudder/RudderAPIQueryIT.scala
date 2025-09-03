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

import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.Scope
import zio.ZIO
import zio.ZIOAppArgs
import zio.ZIOAppDefault

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
        apiTimeout = TimeoutInterval(5),
        checkCertificate = true,
        refreshInterval = TimeoutInterval(30),
        sshDefaultPort = 8080,
        envVarSSLPort = None,
        rundeckDefaultUser = "rundeck",
        envVarRundeckUser = None
      )

      _ <- program(config).provideLayer(HttpClientZioBackend.layer())
    } yield ()

}
