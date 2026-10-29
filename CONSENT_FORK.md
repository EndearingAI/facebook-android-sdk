# Endearing Android consent fork

This fork starts from Meta Android SDK **18.3.0**, upstream commit
`a01d2c597c6c9caa8e4cebbb835ce3acea9f2b56`. The original
[Facebook Platform License](LICENSE.txt) and source notices remain in place.
The SDK-reported version remains `18.3.0`; Maven coordinates identify the patch.

## Host contract

Tracking starts **OFF in every process**. Consent is never restored from disk,
remote settings, an event call, a lifecycle callback, or an initialization call.
The application decides when authenticated installation binding permits tracking.
Android does not require an additional in-app consent prompt for this integration;
the host retains its own age and eligibility policy.

The Java bridge API is:

```java
FacebookSdk.setTrackingEnabled(context, booleanEnabled);
boolean enabled = FacebookSdk.isTrackingEnabled();
String anonymousIdOrNull = FacebookSdk.getAnonymousID();
```

Grant only after the authenticated installation is bound. Keep the manifest and
local settings for automatic event logging and advertiser-ID collection disabled.
When auto-init is disabled, mark the SDK fully initialized before its queued
settings request executes:

```java
FacebookSdk.setAutoLogAppEventsEnabled(false);
FacebookSdk.setAdvertiserIDCollectionEnabled(false);
FacebookSdk.setAutoInitEnabled(false);
FacebookSdk.setTrackingEnabled(application, true);
FacebookSdk.fullyInitialize();
FacebookSdk.sdkInitialize(application, new FacebookSdk.TrackingInitializeCallback() {
  @Override public void onInitialized() {
    AppEventsLogger.activateApp(application);
  }
  @Override public void onInitializationFailed(FacebookException error) {
    // Settle the host's pending initialization and report the failure.
  }
});
```

`activateApp` preserves native Meta install and activation delivery while automatic
logging is disabled. It does not need synthetic events from the bridge. Successful
install bookkeeping remains once per application installation.

Before sign-out or account switching, call
`FacebookSdk.setTrackingEnabled(context, false)`. Withdrawal closes the process
gate, invalidates queued work and old logger instances, cancels timers and active
HTTP, unregisters lifecycle tracking, clears identity and event memory, and erases
SDK-owned event files, preferences, and media/instrumentation caches. The only
preserved preference entries are long-valued `*ping` install markers in
`com.facebook.sdk.attributionTracking`. A fresh grant creates a fresh anonymous
identity and cannot replay withdrawn events.

Erasure is synchronous. If a file or preferences store cannot be erased, the call
throws and tracking remains disabled; retry cleanup before granting. An HTTP
request already accepted by a remote server cannot be recalled. Its late response
cannot revive the SDK or publish state into a later grant.

Joined initialization callbacks settle once. The two-method callback receives
`onInitializationFailed` on cancellation or failure. A legacy one-method callback
settles via `onInitialized` on cancellation, with `isInitialized() == false`.

## Build and publication

Use JDK 17, Android SDK 35, and the checked-in Gradle wrapper. The fork uses Gradle
8.14.3 and Android Gradle Plugin 8.10.1. Samples are opt-in with `-PincludeSamples`.

Every module publishes under the same group and version, including all transitive
SDK modules:

```text
com.github.EndearingAI.facebook-android-sdk:facebook-android-sdk:<full-commit-sha>
```

Do not mix official Meta Android artifacts with this fork. Publish and validate
with:

```sh
FORK_PUBLICATION_VERSION=<full-commit-sha> bash scripts/publish-consent-fork.sh
```

The script builds release AARs and source jars, publishes to local Maven and
`build/fork-maven`, validates every POM and Gradle module metadata edge, then resolves
the umbrella SDK from a separate Gradle project. Both metadata formats must resolve
all nine runtime modules exclusively at the fork group and version. `jitpack.yml`
runs the same script. No signing credentials are required for these artifacts.

## Verification

Run the native consent suite:

```sh
./gradlew :facebook-core:testDebugUnitTest --tests '*TrackingConsentTest' \
  :facebook-common:testDebugUnitTest --tests '*TrackingConsentTest' \
  assembleRelease --console=plain --max-workers=2
```

The suite uses real SDK entry points, Android preferences/files, a local HTTP
request recorder, and real scheduler queues. It covers denied startup and prior
state, native install/activation delivery, local false versus fetched automatic
logging flags, offline event erasure, identity rotation, install-once retention,
stale work and requests, initialization fan-out, raw HTTP and redirects, retained
streams, in-flight settings/gatekeepers, blocked connects, automatic collector
identity and crash-handler cleanup, erasure failure, preference backups, and
WebView withdrawal/subresource denial. Test routing applies only to provider
HTTPS hosts; dependency downloads use the normal HTTPS implementation.

Build outputs, recorded requests, and dependency binaries are not committed.

The full inherited `./gradlew test --continue` suite is **not green** on this
JDK 17 environment. Its PowerMock/XStream harness fails reflective initialization
without opening `java.text`, and legacy tests that expect an active SDK have not
been updated to grant the new process-local consent. In particular,
`WorkQueueTest.test thread stress` waits indefinitely after submitting through a
denied SDK executor. The diagnostic run was stopped after capturing these
failures. No failing test was removed, skipped, or weakened. The independently
configured consent suite above does not depend on that legacy mock harness.
