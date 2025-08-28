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

import org.junit.runner.RunWith
import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class RudderCodecTest extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("json codec test")(
      suite("nodes")(
        test("decoding should succeed when all required fields are present") {

          val json =
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
              |        "properties": [
              |          {
              |            "name": "prop",
              |            "value": "prop_value"
              |          }
              |        ],
              |        "policyMode": "default",
              |        "timezone": {
              |          "name": "UTC",
              |          "offset": "+0000"
              |        },
              |        "environmentVariables": {
              |          "envKey": "envVal"
              |        }
              |      }
              |    ]
              |  }
              |}
              |""".stripMargin

          val decoded = json.fromJson[RudderNodeResponse]

          val expected = RudderNodeResponse(
            action = "listAcceptedNodes",
            result = "success",
            data = NodeData(
              Chunk(
                Node(
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
                  ipAddresses = Chunk(
                    "0:0:0:0:0:0:0:1",
                    "127.0.0.1",
                    "fe80:0:0:0:a00:27ff:fec1:798b",
                    "192.168.4.2",
                    "fe80:0:0:0:a00:27ff:fe8d:c04d",
                    "10.0.2.15"
                  ),
                  lastInventoryDate = Some("2025-08-08T05:55:12Z"),
                  policyServerId = Some("root"),
                  properties = Chunk(Property("prop", "prop_value")),
                  environmentVariables = Some(Map.apply(("envKey", "envVal"))),
                  accounts = None,
                  networkInterfaces = None,
                  storage = None,
                  fileSystems = None
                )
              )
            )
          )

          assert(decoded)(isRight(equalTo(expected)))

        },
        test("decoding should fail when a required field is missing") {
          val json =
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
              |          "192.168.4.2",
              |          "10.0.2.15"
              |        ],
              |        "description": "",
              |        "acceptanceDate": "2025-04-02T12:46:58Z",
              |        "lastInventoryDate": "2025-08-08T05:55:12Z",
              |        "policyServerId": "root"
              |      }
              |    ]
              |  }
              |}
              |""".stripMargin

          val decoded = json.fromJson[RudderNodeResponse]
          val errMsg = ".data.nodes[0].properties(missing)"

          assert(decoded)(isLeft(equalTo(errMsg)))

        },
        test("decoding should fail when several required fields are missing") {
          val json =
            """{
              |  "action": "listAcceptedNodes",
              |  "result": "success",
              |  "data": {
              |    "nodes": [
              |      {
              |        "id": "root",
              |        "hostname": "server.rudder.local",
              |        "ram": 2062548992,
              |        "machine": {
              |          "id": "63a9f0ea-7bb9-8050-796b-649e85481845",
              |          "type": "Virtual",
              |          "provider": "vbox",
              |          "manufacturer": "innotek GmbH",
              |          "serialNumber": "87932606-339f-4e0e-a343-f5e3ece18c55"
              |        },
              |        "os": {
              |          "type": "Linux",
              |          "name": "Debian",
              |          "version": "12",
              |          "fullName": "Debian GNU/Linux 12 (bookworm)",
              |          "kernelVersion": "6.1.0-37-amd64"
              |        },
              |        "description": "",
              |        "acceptanceDate": "2025-04-02T12:46:58Z",
              |        "policyServerId": "root",
              |        "properties": []
              |      }
              |    ]
              |  }
              |}
              |""".stripMargin

          val decoded = json.fromJson[RudderNodeResponse]
          val errMsg = ".data.nodes[0].status(missing)"

          assert(decoded)(isLeft(equalTo(errMsg)))

        }
      ),
      suite("groups")(
        test("decoding should succeed when all required fields are present") {

          val json =
            """{
              |  "action": "listGroups",
              |  "result": "success",
              |  "data": {
              |    "groups": [
              |      {
              |        "id": "my-group",
              |        "displayName": "my-group",
              |        "description": "",
              |        "category": "GroupRoot",
              |        "nodeIds": [
              |          "root"
              |        ],
              |        "dynamic": false,
              |        "enabled": true,
              |        "groupClass": [
              |          "group_my_group",
              |          "group_test_group10"
              |        ],
              |        "properties": [],
              |        "target": "group:my-group",
              |        "system": false
              |      },
              |      {
              |        "id": "other-group",
              |        "displayName": "other-group",
              |        "description": "",
              |        "category": "GroupRoot",
              |        "query": {
              |          "select": "node",
              |          "composition": "and",
              |          "where": []
              |        },
              |        "nodeIds": [
              |          "A"
              |        ],
              |        "dynamic": true,
              |        "enabled": true,
              |        "groupClass": [
              |          "group_other_group",
              |          "group_test_group"
              |        ],
              |        "properties": [],
              |        "target": "group:other-group",
              |        "system": false
              |      },
              |      {
              |        "id": "yet-another-group",
              |        "displayName": "yet-another-group",
              |        "description": "",
              |        "category": "GroupRoot",
              |        "nodeIds": [
              |          "root",
              |          "A"
              |        ],
              |        "dynamic": true,
              |        "enabled": false,
              |        "groupClass": [
              |          "group_yet_another_group",
              |          "group_test_group6"
              |        ],
              |        "properties": [],
              |        "target": "group:yet-another-group",
              |        "system": false
              |      },
              |      {
              |        "id": "all-nodes-with-cfengine-agent",
              |        "displayName": "All Linux Nodes",
              |        "description": "All Linux Nodes known by Rudder",
              |        "category": "SystemGroups",
              |        "query": {
              |          "select": "nodeAndPolicyServer",
              |          "composition": "and",
              |          "where": [
              |            {
              |              "objectType": "node",
              |              "attribute": "agentName",
              |              "comparator": "eq",
              |              "value": "cfengine"
              |            }
              |          ]
              |        },
              |        "nodeIds": [
              |          "root",
              |          "A",
              |          "B"
              |        ],
              |        "dynamic": true,
              |        "enabled": true,
              |        "groupClass": [
              |          "group_all_linux_nodes",
              |          "group_all_nodes_with_cfengine_agent"
              |        ],
              |        "properties": [],
              |        "target": "group:all-nodes-with-cfengine-agent",
              |        "system": true
              |      },
              |      {
              |        "id": "hasPolicyServer-root",
              |        "displayName": "All Linux Nodes managed by root policy server",
              |        "description": "All Linux Nodes known by Rudder directly connected to the root server. This group exists only as internal purpose and should not be used to configure nodes.",
              |        "category": "SystemGroups",
              |        "query": {
              |          "select": "nodeAndPolicyServer",
              |          "composition": "and",
              |          "where": [
              |            {
              |              "objectType": "node",
              |              "attribute": "policyServerId",
              |              "comparator": "eq",
              |              "value": "root"
              |            },
              |            {
              |              "objectType": "node",
              |              "attribute": "agentName",
              |              "comparator": "eq",
              |              "value": "cfengine"
              |            }
              |          ]
              |        },
              |        "nodeIds": [
              |          "root"
              |        ],
              |        "dynamic": true,
              |        "enabled": true,
              |        "groupClass": [
              |          "group_all_linux_nodes_managed_by_root_policy_server",
              |          "group_haspolicyserver_root"
              |        ],
              |        "properties": [],
              |        "target": "group:hasPolicyServer-root",
              |        "system": true
              |      }
              |    ]
              |  }
              |}
              |""".stripMargin

          val decoded = json.fromJson[RudderGroupResponse]

          val expected = RudderGroupResponse(
            action = "listGroups",
            result = "success",
            data = GroupData(
              Chunk(
                Group(
                  id = GroupId("my-group"),
                  displayName = "my-group",
                  nodeIds = Set(NodeId("root")),
                  enabled = true,
                  dynamic = false
                ),
                Group(
                  id = GroupId("other-group"),
                  displayName = "other-group",
                  nodeIds = Set(NodeId("A")),
                  enabled = true,
                  dynamic = true
                ),
                Group(
                  id = GroupId("yet-another-group"),
                  displayName = "yet-another-group",
                  nodeIds = Set(NodeId("root"), NodeId("A")),
                  enabled = false,
                  dynamic = true
                ),
                Group(
                  id = GroupId("all-nodes-with-cfengine-agent"),
                  displayName = "All Linux Nodes",
                  nodeIds = Set(
                    NodeId("root"),
                    NodeId("A"),
                    NodeId("B")
                  ),
                  enabled = true,
                  dynamic = true
                ),
                Group(
                  id = GroupId("hasPolicyServer-root"),
                  displayName = "All Linux Nodes managed by root policy server",
                  nodeIds = Set(NodeId("root")),
                  enabled = true,
                  dynamic = true
                )
              )
            )
          )

          assert(decoded)(isRight(equalTo(expected)))

        },
        test("decoding should succeed if there are no groups") {

          val json =
            """{
              |  "action": "listGroups",
              |  "result": "success",
              |  "data": {
              |    "groups": []
              |  }
              |}
              |""".stripMargin

          val decoded = json.fromJson[RudderGroupResponse]
          val expected =
            RudderGroupResponse("listGroups", "success", GroupData(Chunk.empty))

          assert(decoded)(isRight(equalTo(expected)))

        },
        test("decoding should fail when a required field is missing") {

          val json =
            """{
              |  "action": "listGroups",
              |  "result": "success",
              |  "data": {
              |    "groups": [
              |      {
              |        "id": "bcd19231-0899-42c3-8e92-41c3c0a6fd2f",
              |        "displayName": "test-group3",
              |        "description": "",
              |        "category": "GroupRoot",
              |        "query": {
              |          "select": "nodeAndPolicyServer",
              |          "composition": "and",
              |          "where": [
              |            {
              |              "objectType": "node",
              |              "attribute": "OS",
              |              "comparator": "eq",
              |              "value": "Linux"
              |            }
              |          ]
              |        },
              |        "dynamic": true,
              |        "enabled": true,
              |        "groupClass": [
              |          "group_bcd19231_0899_42c3_8e92_41c3c0a6fd2f",
              |          "group_test_group3"
              |        ],
              |        "properties": [],
              |        "target": "group:bcd19231-0899-42c3-8e92-41c3c0a6fd2f",
              |        "system": false
              |      }
              |    ]
              |  }
              |}
              |""".stripMargin

          val decoded = json.fromJson[RudderGroupResponse]
          val errMsg = ".data.groups[0].nodeIds(missing)"

          assert(decoded)(isLeft(equalTo(errMsg)))

        }
      )
    )
  }
}
