/*
 * Copyright 2015 Normation (http://normation.com)
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

package com.normation.rundeck.plugin.resources.rudder;

import java.util.Properties
import com.dtolabs.rundeck.core.common.Framework
import com.dtolabs.rundeck.core.plugins.Plugin
import com.dtolabs.rundeck.core.plugins.configuration.Describable
import com.dtolabs.rundeck.core.plugins.configuration.PropertyUtil
import com.dtolabs.rundeck.core.resources.ResourceModelSourceFactory
import com.dtolabs.rundeck.plugins.util.DescriptionBuilder
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException


/**
 * This file provides the property description that will be showed to the
 * user in the plugin details page.
 */


@Plugin(name = "rudder", service = "ResourceModelSource")
class RudderResourceModelSourceFactory(framework: Framework) extends ResourceModelSourceFactory with Describable {
  override def createResourceModelSource(properties: Properties) = {
    RudderResourceModelSource.fromProperties(properties) match {
      case Left(ErrorMsg(msg, optex)) => throw new ConfigurationException(msg)
      case Right(x) => x
    }
  }

  override def getDescription() = {
    RudderResourceModelSourceFactory.DESC
  }
}

object RudderResourceModelSourceFactory {

  val PROVIDER_NAME = "rudder"
  val API_TOKEN = "apiToken"
  val RUDDER_API_ENDPOINT = "rudderAPIUrl"
  val REFRESH_INTERVAL = "refreshInterval"
  val RUNDECK_USER = "rundeckUser"

  //  TODO
//  val GROUP_ID = "groupIds"

  /*
   * All the properties are defined here
   */

  val DESC = DescriptionBuilder.builder()
    .name(PROVIDER_NAME)
    .title("Rudder Resources")
    .description("Produces nodes from Rudder")

    .property(PropertyUtil.string(RUDDER_API_ENDPOINT, "Rudder API URL",
        "The URL to access you Rudder API, for ex.: 'https://my.company.com/rudder/api'", true, null))
    .property(PropertyUtil.string(API_TOKEN, "API TOKEN",
        "The API token to use for rundeck, defined in Rudder API administration page", true, null))
    .property(PropertyUtil.integer(REFRESH_INTERVAL, "Refresh Interval",
        "Minimum time in seconds between API requests to AWS (default is 30)", false, "30"))
    .property(PropertyUtil.string(RUNDECK_USER, "Rundeck user",
        "The user used by rundeck to connect to nodes", true, "rundeck"))
    .property(PropertyUtil.string(RUNDECK_USER, "Rundeck user",
        "The user used by rundeck to connect to nodes", true, "rundeck"))
//    .property(PropertyUtil.string(GROUP_IDS, "Group IDs",
//        "Only get nodes from the comma separated list of group UUID. Let empty to get all nodes. Invalid UUIDs will be ignored", false, null))

//    .property(PropertyUtil.string(MAPPING_FILE, "Mapping File", "Property mapping File", false, null,
//            new PropertyValidator() {
//                public boolean isValid(final String s) throws ValidationException {
//                    if (!new File(s).isFile()) {
//                        throw new ValidationException("File does not exist: " + s);
//                    }
//                    return true;
//                }
//            }))
//    .property(PropertyUtil.bool(USE_DEFAULT_MAPPING, "Use Default Mapping",
//            "Start with default mapping definition. (Defaults will automatically be used if no others are " +
//                    "defined.)",
//            false, "true"))
    .build();
}

