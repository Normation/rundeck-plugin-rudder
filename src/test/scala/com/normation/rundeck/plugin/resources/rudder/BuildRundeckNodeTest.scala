package com.normation.rundeck.plugin.resources.rudder

import com.dtolabs.rundeck.core.common.NodeEntryImpl
import zio.Scope
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault}
import zio.test.Assertion.*
import zio.test.*

object BuildRundeckNodeTest extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("Rundeck node build test")(
      test(
        "a Rundeck node entry should be successfully extracted " +
          "from a valid Rudder node and their attributes should be equivalent"
      ) {
        val rudderNode = Node(
          id = "root",
          hostname = "server.rudder.local",
          status = "accepted",
          os = Os(
            `type` = "Linux",
            name = "Debian",
            version = "12",
            fullName = "Debian GNU/Linux 12 (bookworm)",
            kernelVersion = "6.1.0-37-amd64"
          ),
          architectureDescription = "x86_64",
          ram = Some(2062548992),
          ipAddresses = List(
            "0:0:0:0:0:0:0:1",
            "127.0.0.1",
            "192.168.4.2",
            "10.0.2.15"
          ),
          lastInventoryDate = "2025-08-08T05:55:12Z",
          policyServerId = "root",
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

        assert(converted.getAttributes)(equalTo(expected.getAttributes))
      }
    )
  }
}
