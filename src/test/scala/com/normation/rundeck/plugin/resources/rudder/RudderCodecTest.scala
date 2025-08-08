package com.normation.rundeck.plugin.resources.rudder

import zio.Scope
import zio.test.render.ExecutionResult.ResultType.Suite
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertCompletes}
import zio.json.*
import zio.test.Assertion.*
import zio.test.*

class RudderCodecTest

object RudderCodecTest extends ZIOSpecDefault {

  override def spec = {
    suite("json codec test")(test("decode sample json") {

      val json = {
        """{
          |  "action": "listAcceptedNodes",
          |  "result": "success",
          |  "data": {
          |    "nodes": [
          |      {
          |        "id": "root",
          |        "hostname": "server.rudder.local",
          |        "status": "accepted",
          |        "state": "enabled",
          |        "os": {
          |          "type": "Linux",
          |          "name": "Debian",
          |          "version": "12",
          |          "fullName": "Debian GNU/Linux 12 (bookworm)",
          |          "kernelVersion": "6.1.0-37-amd64"
          |        },
          |        "architectureDescription": "x86_64",
          |        "ram": 2062548992,
          |        "machine": {
          |          "id": "63a9f0ea-7bb9-8050-796b-649e85481845",
          |          "type": "Virtual",
          |          "provider": "vbox",
          |          "manufacturer": "innotek GmbH",
          |          "serialNumber": "87932606-339f-4e0e-a343-f5e3ece18c55"
          |        },
          |        "ipAddresses": [
          |          "0:0:0:0:0:0:0:1",
          |          "127.0.0.1",
          |          "fe80:0:0:0:a00:27ff:fec1:798b",
          |          "192.168.4.2",
          |          "fe80:0:0:0:a00:27ff:fe8d:c04d",
          |          "10.0.2.15"
          |        ],
          |        "description": "",
          |        "acceptanceDate": "2025-04-02T12:46:58Z",
          |        "lastInventoryDate": "2025-08-08T05:55:12Z",
          |        "policyServerId": "root",
          |        "managementTechnology": [
          |          {
          |            "name": "Rudder",
          |            "version": "8.2.5-debian12",
          |            "capabilities": [
          |              "acl",
          |              "cfengine",
          |              "curl",
          |              "http_reporting",
          |              "jq",
          |              "xml",
          |              "yaml"
          |            ],
          |            "nodeKind": "root"
          |          }
          |        ],
          |        "properties": [],
          |        "policyMode": "default",
          |        "timezone": {
          |          "name": "UTC",
          |          "offset": "+0000"
          |        }
          |      }
          |    ]
          |  }
          |}
          |""".stripMargin
      }

      val decoded = json.fromJson[RudderNodeResponse]
      pprint.pprintln(decoded)

      val expected = RudderNodeResponse(
        action = "listAcceptedNodes",
        result = "success",
        data = Data(
          List(
            Node(
              id = "root",
              hostname = "server.rudder.local",
              status = "accepted",
              state = "enabled",
              os = Os(
                `type` = "Linux",
                name = "Debian",
                version = "12",
                fullName = "Debian GNU/Linux 12 (bookworm)",
                kernelVersion = "6.1.0-37-amd64"
              ),
              architectureDescription = "x86_64",
              ram = 2062548992,
              machine = Machine(
                id = "63a9f0ea-7bb9-8050-796b-649e85481845",
                `type` = "Virtual",
                provider = "vbox",
                manufacturer = "innotek GmbH",
                serialNumber = "87932606-339f-4e0e-a343-f5e3ece18c55"
              ),
              ipAddresses = List(
                "0:0:0:0:0:0:0:1",
                "127.0.0.1",
                "fe80:0:0:0:a00:27ff:fec1:798b",
                "192.168.4.2",
                "fe80:0:0:0:a00:27ff:fe8d:c04d",
                "10.0.2.15"
              ),
              description = "",
              acceptanceDate = "2025-04-02T12:46:58Z",
              lastInventoryDate = "2025-08-08T05:55:12Z",
              policyServerId = "root",
              managementTechnology = List(
                ManagementTechnology(
                  name = "Rudder",
                  version = "8.2.5-debian12",
                  capabilities = List(
                    "acl",
                    "cfengine",
                    "curl",
                    "http_reporting",
                    "jq",
                    "xml",
                    "yaml"
                  ),
                  nodeKind = "root"
                )
              ),
              properties = List(),
              policyMode = "default",
              timezone = Timezone(name = "UTC", offset = "+0000")
            )
          )
        )
      )

      assert(decoded)(isRight(equalTo(expected)))

    })
  }
}
