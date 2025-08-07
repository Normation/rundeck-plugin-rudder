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

import com.dtolabs.rundeck.core.common.NodeEntryImpl
import org.junit.runner.RunWith
import zio.Scope
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class BuildRundeckNodeTest extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Rundeck node build test")(
      test(
        "a Rundeck node entry should be successfully extracted " +
          "from a valid Rudder node and their attributes should be equivalent"
      ) {
        val rudderNode = Node(
          id = "root",
          hostname = "server.rudder.local",
          status = "accepted",
          os = Some(
            Os(
              `type` = "Linux",
              name = "Debian",
              version = "12",
              fullName = "Debian GNU/Linux 12 (bookworm)",
              kernelVersion = "6.1.0-37-amd64"
            )
          ),
          architectureDescription = Some("x86_64"),
          ram = Some(2062548992),
          ipAddresses = List(
            "0:0:0:0:0:0:0:1",
            "127.0.0.1",
            "192.168.4.2",
            "10.0.2.15"
          ),
          lastInventoryDate = Some("2025-08-08T05:55:12Z"),
          policyServerId = Some("root"),
          properties = List(),
          environmentVariables = None,
          accounts = None,
          networkInterfaces = None,
          storage = None,
          fileSystems = None
        )
        val mockConfig = Configuration(
          url = RudderUrl("http://127.0.0.1:8080/rudder", ApiLatest),
          apiToken = "apiToken",
          apiTimeout = TimeoutInterval(0),
          checkCertificate = false,
          refreshInterval = TimeoutInterval(0),
          sshDefaultPort = 8080,
          envVarSSLPort = None,
          rundeckDefaultUser = "rundeck",
          envVarRundeckUser = None
        )
        val converted = RudderAPIQuery.extractNode(rudderNode, mockConfig)

        val expected = NodeEntryImpl()
        expected.getAttributes
          .put("rudder_information:policy_server_id", "root")
        expected.getAttributes.put(
          "ip_addresses",
          List(
            "0:0:0:0:0:0:0:1",
            "127.0.0.1",
            "192.168.4.2",
            "10.0.2.15"
          ).mkString(", ")
        )
        expected.setOsFamily("Linux")
        expected.setOsName("Debian")
        expected.setOsArch("x86_64")
        expected.setOsVersion("12")
        expected.setNodename("server.rudder.local root")
        expected.setHostname("server.rudder.local:8080")
        expected.setUsername("rundeck")
        expected.setDescription("Debian GNU/Linux 12 (bookworm)")
        expected.getAttributes
          .put("rudder_information:last_inventory_date", "2025-08-08T05:55:12Z")
        expected.getAttributes.put("rudder_information:id", "root")
        expected.getAttributes.put(
          "rudder_information:node_direct_url",
          "http://127.0.0.1:8080/rudder/secure/nodeManager/searchNodes#{\"nodeId\":\"root\"}"
        )
        expected.getAttributes.put("rudder_information:node_status", "accepted")
        expected.getAttributes.put("total_ram", "2062548992")

        assertZIO(converted.map(_.getAttributes))(
          equalTo(expected.getAttributes)
        )
      }
    )
}
