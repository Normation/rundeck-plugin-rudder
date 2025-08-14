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

import zio.Scope
import zio.ZIO
import zio.ZIOAppArgs
import zio.ZIOAppDefault
import zio.http.Client

class Main

object Main extends ZIOAppDefault {

  override def run: ZIO[Any & ZIOAppArgs & Scope, Any, Any] = {

    for {
      args <- getArgs
      apiToken <-
        if (args.isEmpty)
          "Please provide an API token as a program argument.".fail
        else args.head.succeed
      _ <- {
        val config = Configuration(
          url = RudderUrl("http://127.0.0.1:8080/rudder", ApiLatest),
          apiToken = apiToken,
          apiTimeout = TimeoutInterval(0),
          checkCertificate = true,
          refreshInterval = TimeoutInterval(0),
          sshDefaultPort = 8080,
          envVarSSLPort = None,
          rundeckDefaultUser = "rundeck",
          envVarRundeckUser = None
        )
        val clientEnv = Client.default
        RudderAPIQuery.queryNodes(config).debug.provide(clientEnv)
      }
    } yield ()

  }
}
