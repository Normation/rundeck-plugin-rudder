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

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import java.util.Properties
import org.junit.runner.RunWith
import zio.*
import zio.Config.Error.InvalidData
import zio.Config.boolean
import zio.Config.int
import zio.Config.string
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

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

  val conf: Config[Configuration2] =
    rudderUrl("rudderUrl")
      .zip(string("apiToken"))
      .zip(timeoutInterval("apiTimeout"))
      .zip(boolean("apiCheckCertificate"))
      .zip(timeoutInterval("refreshInterval"))
      .zip(port("defaultSshPort"))
      .to[Configuration2]

  def rudderUrl(path: String): Config[RudderUrl] =
    string(path)
      .map(x => RudderUrl.apply(x, ApiLatest))

  def timeoutInterval(path: String): Config[TimeoutInterval] =
    int(path).map(TimeoutInterval.apply)

  def port(path: String): Config[Port] =
    int(path).mapOrFail(
      _.refineEither[PortConstraint].left.map(message =>
        InvalidData(message = message)
      )
    )

  def parse(
      input: Map[String, String]
  ): IO[ConfigurationError, Configuration2] =
    zio.config
      .read(conf.from(ConfigProvider.fromMap(input)))
      .mapError(e => ConfigurationError(e.getMessage()))

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

@RunWith(classOf[ZTestJUnitRunner])
class ConfigSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("configuration parsing")(
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
                "rudderUrl" -> "http://localhost",
                "apiToken" -> "token",
                "apiTimeout" -> "1234",
                "apiCheckCertificate" -> "false",
                "refreshInterval" -> "324",
                "defaultSshPort" -> "12345"
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
              "rudderUrl" -> "http://localhost",
              "apiToken" -> "token",
              "apiTimeout" -> "1234",
              "apiCheckCertificate" -> "false",
              "refreshInterval" -> "324",
              "defaultSshPort" -> "12345"
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
