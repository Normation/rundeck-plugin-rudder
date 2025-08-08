package com.normation.rundeck.plugin.resources.rudder

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import zio.*
import zio.Config.Error.InvalidData
import zio.Config.{boolean, int, string}
import zio.test.*
import zio.test.Assertion.*

import java.util.Properties

type PortConstraint = GreaterEqual[0] & LessEqual[65535]
type Port = Int :| PortConstraint

case class Configuration2(
    url: RudderUrl,
    apiToken: String,
    apiTimeout: TimeoutInterval,
    checkCertificate: Boolean,
    refreshInterval: TimeoutInterval, // time in ms, should never be < 5000ms
    sshDefaultPort: Port
)

object Configuration2 {

  import zio.config.*

  case class ConfigurationError(message: String)

  val conf: Config[Configuration2] = {
    rudderUrl("URL")
      .zip(string("API_TOKEN"))
      .zip(timeoutInterval("TIMEOUT"))
      .zip(boolean("CHECK_CERTIFICATE"))
      .zip(timeoutInterval("REFRESH_INTERVAL"))
      .zip(port("SSH"))
      .to[Configuration2]
  }

  def rudderUrl(path: String): Config[RudderUrl] = {
    string(path)
      .map(x => RudderUrl.apply(x, ApiLatest))
  }

  def timeoutInterval(path: String): Config[TimeoutInterval] =
    int(path).map(TimeoutInterval.apply)

  def port(path: String): Config[Port] = {
    int(path).mapOrFail(
      _.refineEither[PortConstraint].left.map(message =>
        InvalidData(message = message)
      )
    )
  }

  def parse(
      input: Map[String, String]
  ): IO[ConfigurationError, Configuration2] = {
    zio.config
      .read(conf.from(ConfigProvider.fromMap(input)))
      .mapError(e => ConfigurationError(e.getMessage()))
  }

  def parseProperties(
      properties: Properties
  ): IO[ConfigurationError, Configuration2] = {
    import scala.jdk.CollectionConverters._

    parse(
      properties.stringPropertyNames.asScala
        .map(key => key -> properties.getProperty(key))
        .toMap
    )
  }
}

object ConfigSpec extends ZIOSpecDefault {

  val spec = suite("configuration parsing")(
    test("empty configuration should generate an error list")(
      for {
        actual <- Configuration2.parse(Map.empty).either
      } yield assert(actual)(isLeft)
    ),
    test("minimal configuration should parse successfully")(
      for {
        actual <- Configuration2
          .parse(
            Map(
              "URL" -> "http://localhost",
              "API_TOKEN" -> "token",
              "TIMEOUT" -> "1234",
              "CHECK_CERTIFICATE" -> "false",
              "REFRESH_INTERVAL" -> "324",
              "SSH" -> "12345"
            )
          )
          .either
      } yield assert(actual)(isRight)
    ),
    test("minimal properties configuration should parse successfully") {
      import scala.jdk.CollectionConverters._
      val input = {
        val p = new Properties()
        p.putAll(
          Map(
            "URL" -> "http://localhost",
            "API_TOKEN" -> "token",
            "TIMEOUT" -> "1234",
            "CHECK_CERTIFICATE" -> "false",
            "REFRESH_INTERVAL" -> "324",
            "SSH" -> "12345"
          ).asJava
        )
        p
      }
      for {
        actual <- Configuration2.parseProperties(input).either
      } yield assert(actual)(isRight)
    }
  )

}
