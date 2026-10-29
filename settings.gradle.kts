/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// Facebook SDK
include(":facebook-testutil")

include(":facebook-core")

include(":facebook-bolts")

include(
    ":facebook-common",
    ":facebook-login",
    ":facebook-share",
    ":facebook-applinks",
    ":facebook-messenger")

// @fb-only: include(":facebook-livestreaming")

// @fb-only: include(":facebook-beta")

include(":facebook-gamingservices")

include(":facebook")

// Publishing the consent fork builds only the SDK modules.
if (providers.gradleProperty("includeSamples").isPresent) {
  include(":samples:HelloFacebookSample", ":samples:FBLoginSample", ":samples:KotlinSampleApp")
}

if (file("internal/internal-settings.gradle").exists()) {
  apply("internal/internal-settings.gradle")
}

if (file("local.gradle").exists()) {
  apply("local.gradle")
}

// @fb-only: project(":facebook-beta").projectDir = File("internal/facebook-beta")
