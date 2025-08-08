package com.normation.rundeck.plugin.resources.rudder

import zio.http.Client
import zio.{Scope, ZIO, ZIOAppArgs, ZIOAppDefault}

class Main

object Main extends ZIOAppDefault {

  override def run: ZIO[Any & ZIOAppArgs & Scope, Any, Any] = {

    for {
      args <- getArgs
      apiToken <-
        if (args.isEmpty)
          ZIO.fail("Please provide an API token as a program argument.")
        else ZIO.succeed(args.head)
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
