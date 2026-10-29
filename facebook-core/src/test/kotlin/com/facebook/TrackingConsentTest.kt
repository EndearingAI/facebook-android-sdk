/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under LICENSE.txt. */
package com.facebook

import android.app.Application
import android.content.Context
import com.facebook.appevents.AppEventQueue
import com.facebook.appevents.AppEventsLogger
import com.facebook.appevents.FlushReason
import com.facebook.internal.AttributionIdentifiers
import com.facebook.internal.ConsentScheduledExecutor
import com.facebook.internal.FetchedAppSettingsManager
import com.facebook.internal.SdkInitialization
import com.facebook.internal.TrackingConsent
import com.facebook.internal.instrument.InstrumentUtility
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.GzipSource
import okio.buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TrackingConsentTest {
  private lateinit var application: Application
  private lateinit var server: MockWebServer
  private val queued = CopyOnWriteArrayList<Runnable>()
  private var offline = false

  @Before fun setUp() {
    application = RuntimeEnvironment.getApplication()
    FacebookSdk.setTrackingEnabled(application, false)
    FacebookSdk.setApplicationId("123456789")
    FacebookSdk.setClientToken("test-client-token")
    FacebookSdk.setAutoLogAppEventsEnabled(false)
    FacebookSdk.setAdvertiserIDCollectionEnabled(false)
    FacebookSdk.setAutoInitEnabled(false)
    FacebookSdk.setExecutor(Executor { queued.add(it) })
    AppEventsLogger.setFlushBehavior(AppEventsLogger.FlushBehavior.EXPLICIT_ONLY)
    server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        if (offline && request.path.orEmpty().contains("/activities"))
          return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        return MockResponse().setBody(
          if (request.path.orEmpty().contains("gatekeepers")) "{\"data\":[{\"gatekeepers\":[]}]}"
          else if (request.path.orEmpty().contains("/activities")) "{\"success\":true}"
          else "{\"supports_implicit_sdk_logging\":false,\"app_events_feature_bitmask\":0,\"auto_log_app_events_enabled\":true,\"auto_log_app_events_default\":true}")
      }
    }
    server.start()
    System.setProperty(LOOPBACK_PROPERTY, server.url("/").toString())
    val originalHttps = URL::class.java.getDeclaredField("handler").apply { isAccessible = true }
      .get(URL("https://example.invalid")) as URLStreamHandler
    try {
      URL.setURLStreamHandlerFactory { protocol ->
        if (protocol == "https") object : URLStreamHandler() {
          override fun openConnection(url: URL): URLConnection =
            if (url.host == "facebook.com" || url.host.endsWith(".facebook.com") || url.host.endsWith(".fbcdn.net"))
              com.facebook.consent.LoopbackHttpURLConnection(url)
            else URL(null, url.toString(), originalHttps).openConnection()
        } else null
      }
    } catch (alreadyInstalled: Error) {
      if (!alreadyInstalled.message.orEmpty().contains("factory already defined")) throw alreadyInstalled
    }
  }

  @After fun tearDown() {
    FacebookSdk.setTrackingEnabled(application, false)
    queued.clear()
    server.shutdown()
  }

  @Test fun deniedStartupCannotInitializeOrCreateAnonymousIdentity() {
    try { FacebookSdk.sdkInitialize(application) }
    catch (expected: FacebookException) { assertTrue(expected.message.orEmpty().lowercase().contains("consent")) }
    assertFalse("SDK initialization requires explicit consent in this process", FacebookSdk.isInitialized())
    assertNull(FacebookSdk.getAnonymousID())
    assertNull(preferences().getString("anonymousAppDeviceGUID", null))
    assertTrue("Denied startup must not queue network, settings, or tracking work", queued.isEmpty())
    assertEquals(0, server.requestCount)
    assertNull(AttributionIdentifiers.getAttributionIdentifiers(application))
  }

  @Test fun deniedStartupPurgesPreviousProcessDataBeforeAnyRequest() {
    preferences().edit().putString("anonymousAppDeviceGUID", "previous-user").commit()
    application.getSharedPreferences("com.facebook.sdk.APP_SETTINGS", 0).edit()
      .putString("settings", "previous-settings").commit()
    application.getFileStreamPath("AppEventsLogger.persistedevents").writeText("previous-events")
    FacebookSdk.sdkInitialize(application, FacebookSdk.InitializeCallback {})
    assertFalse(application.getFileStreamPath("AppEventsLogger.persistedevents").exists())
    assertTrue(preferences().all.isEmpty())
    assertTrue(application.getSharedPreferences("com.facebook.sdk.APP_SETTINGS", 0).all.isEmpty())
    assertEquals(0, server.requestCount)
  }

  @Test fun explicitGrantSendsNativeInstallAndActivateWithoutAutomaticPayload() {
    grantAndInitialize()
    assertNotNull(FacebookSdk.getAnonymousID())
    AppEventsLogger.activateApp(application)
    drainSdk()
    flushEvents()
    val activities = recorded().filter { it.path.orEmpty().contains("/activities") }.map(::body)
    assertTrue("Native install must reach the provider", activities.any { it.contains("MOBILE_APP_INSTALL") })
    assertTrue("Native activation must reach the provider", activities.any { it.contains("fb_mobile_activate_app") })
    assertTrue("Manual attribution must omit extended device data", activities.none { it.contains("extinfo") || it.contains("advertiser_id=") })
    assertFalse("Fetched migration values cannot override local false", FacebookSdk.getAutoLogAppEventsEnabled())
    assertNull(AttributionIdentifiers.getAttributionIdentifiers(application)?.androidAdvertiserId)
  }

  @Test fun manualAttributionWithAdvertiserIdCannotFetchCodelessSetup() {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
        if (request.path.orEmpty().contains("/activities")) "{\"success\":true}"
        else "{\"supports_implicit_sdk_logging\":false,\"app_events_feature_bitmask\":32,\"auto_log_app_events_enabled\":true}")
    }
    FacebookSdk.setAdvertiserIDCollectionEnabled(true)
    FacebookSdk.setTrackingEnabled(application, true)
    seedAdvertiserId("gaid-codeless-probe")
    FacebookSdk.fullyInitialize()
    FacebookSdk.sdkInitialize(application, FacebookSdk.InitializeCallback {})
    drainSdk()
    AppEventsLogger.activateApp(application)
    drainSdk()
    flushEvents()
    val requests = recorded()
    val activities = requests.filter { it.path.orEmpty().contains("/activities") }.map(::body)
    assertTrue("Native install must still be delivered", activities.any { it.contains("MOBILE_APP_INSTALL") })
    assertTrue("Native app activation must still be delivered", activities.any { it.contains("fb_mobile_activate_app") })
    assertTrue("The GAID fixture must reach the permitted attribution payload", activities.any { it.contains("gaid-codeless-probe") })
    assertTrue("Automatic logging off must forbid advertiser-ID codeless configuration requests", requests.none {
      it.requestUrl?.queryParameter("fields") == "auto_event_setup_enabled" ||
        it.requestUrl?.queryParameter("advertiser_id") == "gaid-codeless-probe"
    })
    assertFalse(FacebookSdk.getAutoLogAppEventsEnabled())
  }

  @Test fun disablingAutomaticLoggingCancelsQueuedCodelessLookup() {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
        "{\"supports_implicit_sdk_logging\":false,\"app_events_feature_bitmask\":32,\"auto_log_app_events_enabled\":true}")
    }
    UserSettingsManager.setAutoLogAppEventsEnabled(true)
    FacebookSdk.setAdvertiserIDCollectionEnabled(true)
    FacebookSdk.setTrackingEnabled(application, true)
    seedAdvertiserId("gaid-codeless-probe")
    FacebookSdk.fullyInitialize()
    FacebookSdk.sdkInitialize(application, FacebookSdk.InitializeCallback {})
    FacebookSdk.setAutoLogAppEventsEnabled(false)
    drainSdk()
    assertTrue("A queued automatic lookup must recheck the local setting before collecting a GAID", recorded().none {
      it.requestUrl?.queryParameter("fields") == "auto_event_setup_enabled" ||
        it.requestUrl?.queryParameter("advertiser_id") == "gaid-codeless-probe"
    })
  }

  @Test fun heldGraphRequestSettlesWithOnePurgedCancellationAfterRegrant() {
    grantAndInitialize()
    val started = CountDownLatch(1)
    val released = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.path.orEmpty().contains("/activities")) { started.countDown(); released.await(5, TimeUnit.SECONDS) }
        return MockResponse().setBody("{\"success\":true,\"private_response\":\"old-response\"}")
      }
    }
    val responses = CopyOnWriteArrayList<GraphResponse>()
    val request = privateGraphRequest { responses.add(it) }
    val batch = GraphRequestBatch(request).apply { callbackHandler = android.os.Handler(android.os.Looper.getMainLooper()) }
    val work = FutureTask { batch.executeAndWait() }
    Thread(work).start()
    assertTrue(started.await(5, TimeUnit.SECONDS))
    FacebookSdk.setTrackingEnabled(application, false)
    FacebookSdk.setTrackingEnabled(application, true)
    released.countDown()
    work.get(5, TimeUnit.SECONDS)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals("Withdrawal must settle a held Graph callback exactly once", 1, responses.size)
    assertPurgedCancellation(responses.single())
  }

  @Test fun postedGraphSuccessBecomesOnePurgedCancellationBeforeCallbackRuns() {
    grantAndInitialize()
    val responses = CopyOnWriteArrayList<GraphResponse>()
    val request = privateGraphRequest { responses.add(it) }
    val batch = GraphRequestBatch(request).apply { callbackHandler = android.os.Handler(android.os.Looper.getMainLooper()) }
    val batchCalls = AtomicInteger()
    batch.addCallback { batchCalls.incrementAndGet() }
    val work = FutureTask { batch.executeAndWait() }
    Thread(work).start()
    val received = work.get(5, TimeUnit.SECONDS)
    assertNull(received.single().error)
    assertEquals(0, responses.size)
    FacebookSdk.setTrackingEnabled(application, false)
    FacebookSdk.setTrackingEnabled(application, true)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals("A posted success must settle once as cancellation", 1, responses.size)
    assertEquals(1, batchCalls.get())
    assertPurgedCancellation(responses.single())
    assertNull("Pending responses must release their old payload", received.single().rawResponse)
  }

  @Test fun postedGraphErrorReleasesProviderMessageAndBodyOnWithdrawal() {
    grantAndInitialize()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(400).setBody(
        "{\"error\":{\"message\":\"old-user-error\",\"type\":\"OAuthException\",\"code\":100}}")
    }
    val responses = CopyOnWriteArrayList<GraphResponse>()
    val request = privateGraphRequest { responses.add(it) }
    val batch = GraphRequestBatch(request).apply { callbackHandler = android.os.Handler(android.os.Looper.getMainLooper()) }
    val work = FutureTask { batch.executeAndWait() }
    Thread(work).start()
    val received = work.get(5, TimeUnit.SECONDS)
    assertEquals("old-user-error", received.single().error?.errorMessage)
    FacebookSdk.setTrackingEnabled(application, false)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals(1, responses.size)
    assertPurgedCancellation(responses.single())
    assertPurgedCancellation(received.single())
  }

  @Test fun queuedAsyncGraphRequestSettlesCancellationWithoutStartingNetwork() {
    grantAndInitialize()
    recorded()
    val responses = CopyOnWriteArrayList<GraphResponse>()
    val request = privateGraphRequest { responses.add(it) }
    val task = request.executeAsync()
    FacebookSdk.setTrackingEnabled(application, false)
    FacebookSdk.setTrackingEnabled(application, true)
    drainSdk()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals("Queued AsyncTask cancellation must settle its callback", 1, responses.size)
    assertPurgedCancellation(responses.single())
    assertTrue("The pending AsyncTask must be cancelled", task.isCancelled)
    assertTrue(recorded().isEmpty())
  }

  @Test fun ordinaryGraphSuccessDoesNotReceiveAnotherCallbackOnWithdrawal() {
    grantAndInitialize()
    val responses = CopyOnWriteArrayList<GraphResponse>()
    val request = privateGraphRequest { responses.add(it) }
    val batch = GraphRequestBatch(request).apply { callbackHandler = android.os.Handler(android.os.Looper.getMainLooper()) }
    val work = FutureTask { batch.executeAndWait() }
    Thread(work).start(); work.get(5, TimeUnit.SECONDS)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals(1, responses.size)
    assertNull(responses.single().error)
    FacebookSdk.setTrackingEnabled(application, false)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals("A delivered success is already settled", 1, responses.size)
  }

  @Test fun mixedGenerationBatchCancelsEveryMemberWithoutBorrowingFreshConsent() {
    grantAndInitialize()
    val responses = CopyOnWriteArrayList<GraphResponse>()
    val forbiddenWork = AtomicInteger()
    var nestedGrants = 0
    val callback: (GraphResponse) -> Unit = { response ->
      responses.add(response)
      FacebookSdk.getExecutor().execute { forbiddenWork.incrementAndGet() }
      try { FacebookSdk.setTrackingEnabled(application, true); nestedGrants++ }
      catch (rejected: IllegalStateException) { /* Cancellation is not a new grant authority. */ }
    }
    val stale = privateGraphRequest(callback)
    FacebookSdk.setTrackingEnabled(application, false)
    grantAndInitialize()
    val fresh = privateGraphRequest(callback)
    recorded()
    val batch = GraphRequestBatch(fresh, stale).apply { callbackHandler = android.os.Handler(android.os.Looper.getMainLooper()) }
    val work = FutureTask { batch.executeAndWait() }
    Thread(work).start(); work.get(5, TimeUnit.SECONDS)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    drainSdk()
    assertEquals(2, responses.size)
    responses.forEach { assertPurgedCancellation(it) }
    assertEquals(0, nestedGrants)
    assertEquals(0, forbiddenWork.get())
    assertTrue(recorded().isEmpty())
    assertTrue("Cancelling the invalid batch must not revoke the new host grant", FacebookSdk.isTrackingEnabled())
  }

  @Test fun initializationCancellationCannotRegrantDuringWithdrawal() {
    FacebookSdk.setTrackingEnabled(application, true)
    var failures = 0
    FacebookSdk.sdkInitialize(application, object : FacebookSdk.TrackingInitializeCallback {
      override fun onInitialized() { fail("Initialization should still be queued") }
      override fun onInitializationFailed(error: FacebookException) {
        failures++
        try {
          FacebookSdk.setTrackingEnabled(application, true)
          FacebookSdk.sdkInitialize(application, FacebookSdk.InitializeCallback {})
        } catch (rejected: IllegalStateException) { /* Nested grants are invalid. */ }
      }
    })
    FacebookSdk.setTrackingEnabled(application, false)
    assertEquals(1, failures)
    assertFalse("The outer withdrawal must return with tracking off", FacebookSdk.isTrackingEnabled())
    assertNull(FacebookSdk.getAnonymousID())
  }

  @Test fun offlineActivationIsErasedAndCannotReplayAfterRegrant() {
    grantAndInitialize()
    offline = true
    AppEventsLogger.activateApp(application)
    drainSdk()
    flushEvents()
    drainSdk()
    assertTrue("Offline native event must be persisted before withdrawal", application.getFileStreamPath("AppEventsLogger.persistedevents").exists())
    FacebookSdk.setTrackingEnabled(application, false)
    assertFalse(application.getFileStreamPath("AppEventsLogger.persistedevents").exists())
    recorded()
    offline = false
    grantAndInitialize()
    flushEvents()
    assertTrue("Old activation must not replay", recorded().none { body(it).contains("fb_mobile_activate_app") })
  }

  @Test fun withdrawalRotatesIdentityAndPreservesOnlyInstallOnceMarker() {
    grantAndInitialize()
    val oldIdentity = FacebookSdk.getAnonymousID()
    val markers = application.getSharedPreferences("com.facebook.sdk.attributionTracking", 0)
    markers.edit().putLong("123456789ping", 42L).putString("user", "old-user").commit()
    val identify = FutureTask { AppEventsLogger.setUserID("old-user") }
    Thread(identify).start()
    identify.get(5, TimeUnit.SECONDS)
    drainSdk()
    FacebookSdk.setTrackingEnabled(application, false)
    assertNull(FacebookSdk.getAnonymousID())
    assertEquals(mapOf("123456789ping" to 42L), markers.all)
    grantAndInitialize()
    assertNotEquals(oldIdentity, FacebookSdk.getAnonymousID())
    assertNull(AppEventsLogger.getUserID())
    recorded()
    AppEventsLogger.activateApp(application)
    drainSdk()
    flushEvents()
    assertTrue("Successful install is once per app installation", recorded().none { body(it).contains("MOBILE_APP_INSTALL") })
  }

  @Test fun queuedWorkAndRequestsCannotCrossWithdrawAndRegrant() {
    grantAndInitialize()
    val ran = AtomicInteger()
    FacebookSdk.getExecutor().execute { ran.incrementAndGet() }
    val stale = GraphRequest.newGraphPathRequest(null, "stale-request", null)
    FacebookSdk.setTrackingEnabled(application, false)
    grantAndInitialize()
    val before = server.requestCount
    assertNotNull(stale.executeAndWait().error)
    assertEquals(before, server.requestCount)
    assertEquals(0, ran.get())
  }

  @Test fun withdrawalSettlesAllJoinedInitializationCallbacksExactlyOnce() {
    FacebookSdk.setTrackingEnabled(application, true)
    val failures = AtomicInteger()
    val successes = AtomicInteger()
    val callback = object : FacebookSdk.TrackingInitializeCallback {
      override fun onInitialized() { successes.incrementAndGet() }
      override fun onInitializationFailed(error: FacebookException) { failures.incrementAndGet() }
    }
    FacebookSdk.sdkInitialize(application, callback)
    FacebookSdk.sdkInitialize(application, callback)
    var legacy = 0
    FacebookSdk.sdkInitialize(application, FacebookSdk.InitializeCallback { assertFalse(FacebookSdk.isInitialized()); legacy++ })
    FacebookSdk.setTrackingEnabled(application, false)
    drainSdk()
    assertEquals(2, failures.get())
    assertEquals(0, successes.get())
    assertEquals(1, legacy)
    grantAndInitialize()
    assertEquals(2, failures.get())
  }

  @Test fun throwingInitializationCallbackDoesNotStrandOtherCallers() {
    FacebookSdk.setTrackingEnabled(application, true)
    val attempt = SdkInitialization(TrackingConsent.capture())
    var settled = 0
    attempt.add(FacebookSdk.InitializeCallback { throw IllegalStateException("caller failure") })
    attempt.add(FacebookSdk.InitializeCallback { settled++ })
    try { attempt.succeed() } catch (expected: IllegalStateException) { /* The caller owns its failure. */ }
    assertEquals("One caller's exception must not strand joined callers", 1, settled)
  }

  @Test fun staleScheduledWorkIsCancelledAndFreshWorkRuns() {
    FacebookSdk.setTrackingEnabled(application, true)
    val scheduler = ConsentScheduledExecutor(1)
    try {
      val old = scheduler.schedule({ fail("Old scheduled work ran") }, 1, TimeUnit.DAYS)
      FacebookSdk.setTrackingEnabled(application, false)
      FacebookSdk.setTrackingEnabled(application, true)
      assertTrue(old.isCancelled)
      assertEquals(7, scheduler.submit<Int> { 7 }.get(5, TimeUnit.SECONDS))
    } finally { scheduler.shutdownNow() }
  }

  @Test fun retainedNetworkStreamsCannotReadOrWriteAfterWithdrawal() {
    FacebookSdk.setTrackingEnabled(application, true)
    val output = ByteArrayOutputStream()
    val raw = object : HttpURLConnection(URL("http://unused.invalid")) {
      override fun connect() {}
      override fun disconnect() {}
      override fun usingProxy() = false
      override fun getInputStream() = ByteArrayInputStream("stale-data".toByteArray())
      override fun getOutputStream() = output
    }
    val connection = TrackingConsent.adopt(raw, TrackingConsent.capture())
    val input = connection.inputStream
    val stream = connection.outputStream
    FacebookSdk.setTrackingEnabled(application, false)
    assertThrows(IOException::class.java) { input.read() }
    assertThrows(IOException::class.java) { stream.write(42) }
    assertEquals(0, output.size())
  }

  @Test fun redirectsAndRawConnectionsCannotBypassWithdrawal() {
    grantAndInitialize()
    val raw = server.url("/raw").toUrl().openConnection() as HttpURLConnection
    FacebookSdk.setTrackingEnabled(application, false)
    val denied = GraphRequest.newGraphPathRequest(null, "denied", null)
    val before = server.requestCount
    val responses = GraphRequest.executeConnectionAndWait(raw, listOf(denied))
    assertNotNull(responses.single().error)
    assertEquals(before, server.requestCount)
    FacebookSdk.setTrackingEnabled(application, true)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect-target"))
    }
    val connection = TrackingConsent.openConnection(server.url("/redirect").toUrl())
    connection.instanceFollowRedirects = true
    assertEquals(302, connection.responseCode)
    connection.disconnect()
    assertEquals(before + 1, server.requestCount)
  }

  @Test fun deniedDiagnosticsCannotCreatePersistentReports() {
    InstrumentUtility.writeFile("crash_log_probe.json", "private-stack")
    assertFalse(java.io.File(application.cacheDir, "instrument/crash_log_probe.json").exists())
  }

  @Test fun purgeFailureKeepsTrackingOffUntilErasureSucceeds() {
    val context = object : android.content.ContextWrapper(application) {
      var rejectErasure = true
      override fun getApplicationContext(): Context = this
      override fun getFileStreamPath(name: String): java.io.File {
        if (name == "AppEventsLogger.persistedevents" && rejectErasure) return object : java.io.File(super.getFileStreamPath(name).path) {
          override fun exists() = true
          override fun delete() = false
        }
        return super.getFileStreamPath(name)
      }
    }
    try {
      assertThrows(IllegalStateException::class.java) { FacebookSdk.setTrackingEnabled(context, false) }
      assertFalse(FacebookSdk.isTrackingEnabled())
      assertThrows(IllegalStateException::class.java) { FacebookSdk.setTrackingEnabled(context, true) }
      assertFalse(FacebookSdk.isTrackingEnabled())
      assertEquals(0, server.requestCount)
    } finally { context.rejectErasure = false; FacebookSdk.setTrackingEnabled(application, false) }
  }

  @Test fun preferenceBackupsArePurgedUnderTheirOriginalName() {
    val directory = java.io.File(application.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
    val backup = java.io.File(directory, "com.facebook.consent.backup.xml.bak")
    backup.writeText("""<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><string name="user">previous-user</string></map>""")
    FacebookSdk.setTrackingEnabled(application, false)
    assertFalse("A backup must not restore a withdrawn user's identity", application.getSharedPreferences("com.facebook.consent.backup", 0).contains("user"))
  }

  @Test fun inFlightSettingsResponseCannotRepopulateAfterWithdrawal() {
    grantAndInitialize()
    val started = CountDownLatch(1)
    val responseAllowed = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        started.countDown()
        responseAllowed.await(5, TimeUnit.SECONDS)
        return MockResponse().setBody("{\"app_events_session_timeout\":600,\"auto_log_app_events_enabled\":true}")
      }
    }
    val request = FutureTask { FetchedAppSettingsManager.queryAppSettings("987654321", true) }
    Thread(request, "settings-consent-test").start()
    if (!started.await(5, TimeUnit.SECONDS)) { request.get(1, TimeUnit.SECONDS); fail("Settings query did not reach transport") }
    FacebookSdk.setTrackingEnabled(application, false)
    FacebookSdk.setTrackingEnabled(application, true)
    responseAllowed.countDown()
    try { request.get(5, TimeUnit.SECONDS) } catch (cancelled: java.util.concurrent.ExecutionException) {
      assertTrue(cancelled.cause is java.util.concurrent.CancellationException)
    }
    assertNull(FetchedAppSettingsManager.getAppSettingsWithoutQuery("987654321"))
  }

  @Test fun withdrawalClearsAutomaticCollectorIdentity() {
    FacebookSdk.setTrackingEnabled(application, true)
    val previous = com.facebook.appevents.codeless.CodelessManager.getCurrentDeviceSessionID()
    FacebookSdk.setTrackingEnabled(application, false)
    FacebookSdk.setTrackingEnabled(application, true)
    assertNotEquals(previous, com.facebook.appevents.codeless.CodelessManager.getCurrentDeviceSessionID())
  }

  @Test fun withdrawalRestoresPreviousCrashHandler() {
    grantAndInitialize()
    FacebookSdk.setAutoLogAppEventsEnabled(true)
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    try {
      com.facebook.internal.instrument.crashreport.CrashHandler.enable()
      assertNotSame(previous, Thread.getDefaultUncaughtExceptionHandler())
      FacebookSdk.setTrackingEnabled(application, false)
      assertSame("Withdrawal must uninstall the SDK's crash observer", previous, Thread.getDefaultUncaughtExceptionHandler())
    } finally { Thread.setDefaultUncaughtExceptionHandler(previous) }
  }

  @Test fun inFlightGatekeeperCannotPersistAfterWithdrawal() {
    grantAndInitialize()
    val started = CountDownLatch(1)
    val released = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        started.countDown(); released.await(5, TimeUnit.SECONDS)
        return MockResponse().setBody("{\"data\":[{\"gatekeepers\":[{\"key\":\"old_user\",\"value\":true}]}]}")
      }
    }
    val work = FutureTask { com.facebook.internal.FetchedAppGateKeepersManager.queryAppGateKeepers("987654321", true) }
    Thread(work).start()
    assertTrue(started.await(5, TimeUnit.SECONDS))
    FacebookSdk.setTrackingEnabled(application, false)
    FacebookSdk.setTrackingEnabled(application, true)
    released.countDown()
    try { work.get(5, TimeUnit.SECONDS) } catch (cancelled: java.util.concurrent.ExecutionException) {
      assertTrue(cancelled.cause is java.util.concurrent.CancellationException)
    }
    assertTrue(application.getSharedPreferences("com.facebook.internal.preferences.APP_GATEKEEPERS", 0).all.isEmpty())
  }

  @Test fun withdrawalDoesNotWaitForABlockedSocketConnect() {
    FacebookSdk.setTrackingEnabled(application, true)
    val started = CountDownLatch(1)
    val released = CountDownLatch(1)
    val raw = object : HttpURLConnection(URL("http://unused.invalid")) {
      override fun connect() { started.countDown(); released.await(5, TimeUnit.SECONDS) }
      override fun disconnect() {}
      override fun usingProxy() = false
    }
    val connection = TrackingConsent.adopt(raw, TrackingConsent.capture())
    val connecting = FutureTask { try { connection.connect() } catch (expected: IOException) {} }
    Thread(connecting).start()
    assertTrue(started.await(5, TimeUnit.SECONDS))
    val revocation = FutureTask { FacebookSdk.setTrackingEnabled(application, false) }
    Thread(revocation).start()
    try {
      revocation.get(1, TimeUnit.SECONDS)
      assertFalse(FacebookSdk.isTrackingEnabled())
    } finally { released.countDown(); connecting.get(5, TimeUnit.SECONDS); revocation.get(5, TimeUnit.SECONDS) }
  }

  private fun seedAdvertiserId(value: String) {
    val identifiers = AttributionIdentifiers()
    AttributionIdentifiers::class.java.getDeclaredField("androidAdvertiserIdValue").apply {
      isAccessible = true; set(identifiers, value)
    }
    AttributionIdentifiers::class.java.getDeclaredField("fetchTime").apply {
      isAccessible = true; setLong(identifiers, System.currentTimeMillis())
    }
    AttributionIdentifiers.cachedIdentifiers = identifiers
  }

  private fun privateGraphRequest(callback: (GraphResponse) -> Unit): GraphRequest =
    GraphRequest.newPostRequest(null, "123456789/activities", JSONObject().put("private_action", "old-user"), GraphRequest.Callback(callback)).apply {
      parameters.putString("private_parameter", "old-user")
      tag = "old-user-tag"
    }

  private fun assertPurgedCancellation(response: GraphResponse) {
    assertNotNull(response.error)
    assertTrue(response.error!!.exception?.message.orEmpty().contains("consent"))
    assertNull(response.error!!.requestResultBody)
    assertNull(response.error!!.requestResult)
    assertNull(response.error!!.batchRequestResult)
    assertNull(response.error!!.connection)
    assertFalse(response.error!!.errorMessage.orEmpty().contains("old-user"))
    assertNull(response.rawResponse)
    assertNull(response.jsonObject)
    assertNull(response.jsonArray)
    assertNull(response.connection)
    assertTrue(response.request.parameters.isEmpty)
    assertNull(response.request.graphObject)
    assertNull(response.request.accessToken)
    assertNull(response.request.tag)
    assertNull(response.request.graphPath)
  }

  private fun grantAndInitialize() {
    FacebookSdk.setTrackingEnabled(application, true)
    FacebookSdk.fullyInitialize()
    var complete = false
    FacebookSdk.sdkInitialize(application, FacebookSdk.InitializeCallback { complete = true })
    drainSdk()
    assertTrue("Initialization callback must settle", complete)
    assertTrue(FacebookSdk.isInitialized())
  }

  private fun drainSdk() {
    var runs = 0
    while (queued.isNotEmpty()) {
      check(++runs < 100) { "SDK work did not settle" }
      val task = FutureTask { queued.removeAt(0).run() }
      Thread(task, "sdk-work-test").start()
      task.get(10, TimeUnit.SECONDS)
    }
  }

  private fun flushEvents() {
    val field = AppEventQueue::class.java.getDeclaredField("singleThreadExecutor").apply { isAccessible = true }
    val executor = field.get(null) as ScheduledThreadPoolExecutor
    executor.submit { AppEventQueue.flushAndWait(FlushReason.EXPLICIT) }.get(10, TimeUnit.SECONDS)
    drainSdk()
  }

  private fun preferences() = application.getSharedPreferences(FacebookSdk.APP_EVENT_PREFERENCES, Context.MODE_PRIVATE)
  private fun recorded(): List<RecordedRequest> {
    val requests = mutableListOf<RecordedRequest>()
    while (true) { requests.add(server.takeRequest(50, TimeUnit.MILLISECONDS) ?: break) }
    return requests
  }
  private fun body(request: RecordedRequest): String =
    if (request.body.size == 0L) ""
    else if (request.getHeader("Content-Encoding") == "gzip") GzipSource(request.body.clone()).buffer().readUtf8()
    else request.body.clone().readUtf8()
  companion object { private const val LOOPBACK_PROPERTY = "com.facebook.consent.test.loopback" }
}
