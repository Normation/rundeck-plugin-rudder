Rundeck Rudder Nodes Plugin
===========================

Version: 0.1

This is a Resource Model Source plugin for [RunDeck][] 2.5+ that provides
Rudder node as nodes for the RunDeck server.

[RunDeck]: http://rundeck.org


Installation
------------

Download from the [releases page](https://github.com/rundeck-plugins/rundeck-rudder-nodes-plugin/releases).

Put the `rundeck-rudder-nodes-plugin-0.1.jar` into your `$RDECK_BASE/libext` dir.

Alternatively, you can build the project from source with Maven (mvn install) and get the
resulting jar from your local repository (see Maven console output for exact location). 

Usage
-----

You can configure the Resource Model Sources for a project either via the
RunDeck GUI, under the "Admin" page, or you can modify the `project.properties`
file to configure the sources.

See: [Resource Model Source Configuration](http://rundeck.org/docs/plugins-user-guide/configuring.html#resource-model-sources)

The provider name is: `rudder`


Authenticating to Rudder Nodes with Rundeck
-----------

Once you get your Rudder nodes listed in Rundeck, you may be wondering "Now how do I use this?"

Rundeck uses SSH by default with private key authentication, so in order to connect to your Rudder nodes out
of the box you will need to configure Rundeck to use the right private SSH key to connect to your nodes,
which can be done in either of a few ways:

1. Copy your private key to the default location used by Rundeck which is `~/.ssh/id_rsa`
2. Copy your private key elsewhere, and override it on a project level. Change project.properties and set the `project.ssh-keypath` to point to the file.

Next, you will have to configure user and SSH port to use for Rundeck connection. For that, you can configure default values in the plugin
properties, and optionaly, an environment variable name whose value can be use on a per-node basis.  

