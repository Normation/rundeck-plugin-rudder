Change Log
==========

3.0 (2025-09-01)
----------------


Dependency and build updates :
* Upgraded to Scala 3.7.1
* Upgraded rundeck-core to v4.0.0-0322, which is compatible with later versions of rundeck-core
* Replaced old/deprecated/vulnerable dependencies with a ZIO stack (zio, zio-json, zio-http)
* The Maven build procedure has been updated : code formatting is now enforced with a basic scalafmt, and tests are executed during the `package` phase
* The project now has a CI pipeline with Github actions (see [ maven.yml](https://github.com/Normation/rundeck-plugin-rudder/actions/workflows/maven.yml)). Its purpose is to build the project every time a PR or branch is updated


Notable changes :
* When importing Rudder nodes to Rundeck, the entire process would fail if one or more Rudder nodes did not possess one of the four attributes that are required in Rundeck, but optional in Rudder (i.e. `os`, `architectureDescription`, `lastInventoryDate`, and `policyServerId`). From now on, the nodes that are missing one or more required attributes will produce a warning log, which will not prevent other nodes from being imported : all viable Rudder nodes will be imported as normal.
* The Rudder API version that should be used can be defined with the `api version` parameter in the plugin configuration. In the previous version, the plugin used to support api versions `12` and `latest`. From now on, the only accepted version is `latest` (though the format of API v12 / Rudder 6 and up is still compatible).

Bug fixes :
* The plugin `.jar` was missing a slf4j logger implementation. Hence, none of the logs that were defined in the code were actually produced. For this reason, the slf4j-simple implementation is now included in the dependencies. By default, the minimum log level is INFO, and it can be customised by adding a property to the JVM : `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`
* The `refreshInterval` parameter was not compatible with later Rundeck versions (the Rudder nodes and groups were systematically refreshed regardless of the specified interval). This is now fixed; the Rudder nodes and groups will not be updated more frequently than the specified `refreshInterval`.

Documentation :
* The installation and usage instructions in the README have been updated
* The plugin `.jar` has a size of around ~40MB, which exceeds the maximum file upload size of the Rundeck web interface. 2 workarounds to install the plugin are now documented in the README


2.3 (2024-08-19)
----------------

Normalize node attribute names

2.2 (2024-05-24)
----------------

Add support for Rudder 8.x.

2.1 (2022-05-12)
----------------

Update to rundeck-core version 3.3.18-20220118 (multiple vulnerability fixes)

2.0 (2021-07-15)
----------------

- Add support for Rudder 6.x
- Update to rundeck-core version 3.3 

1.x (2016-11-21)
----------------

Support for Rudder < 6.0
