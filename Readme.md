Rundeck Rudder Nodes Plugin
===========================

Version: 2.0

This is a Resource Model Source plugin for [RunDeck][] 3.3.x that provides
Rudder node as nodes for the RunDeck server.

[RunDeck]: http://rundeck.org

It works with Rudder standard API v12 for Rudder 6.x and up.

Installation
------------

Download from the [releases page](https://github.com/Normation/rundeck-plugin-rudder/releases).

Put the `rundeck-rudder-nodes-plugin-X.Y.jar` into your `$RDECK_BASE/libext` dir.

Alternatively, you can build the project from source with Maven (mvn install) and get the
resulting jar from your local repository (see Maven console output for exact location).



> **_NOTE:_**: It is old and builds needs a JDK 1.8.x


Usage
-----

Documentation about available properties for the plugin is available in the "Admin"
page, in "Resource Model Sources" > "Rudder Resources",

You can configure the Resource Model Sources on a per-project basis, via the RunDeck GUI.
When a project is selected in RunDeck main bar, select the "Configure" button, then
"Simple Configuration" > "Resource Model Source".

Here, you need to "Add Source" > "Rudder Resources", and file the fields.

You can also modify the `${rundeck_base_dir}/projects/${project_name}/etc/project.properties`
file directly to configure the sources.

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

