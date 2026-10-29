/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under LICENSE.txt. */
package com.facebook.internal

import android.app.Activity
import android.content.Context
import android.webkit.WebView
import com.facebook.FacebookSdk
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebDialogTrackingConsentTest {
  private lateinit var activity: Activity
  private class ProbeDialog(context: Context) : WebDialog(context, "https://m.facebook.com/dialog/oauth") {
    fun view(): WebView? = webView
  }
  @Before fun setup() {
    activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    FacebookSdk.setTrackingEnabled(activity, false)
    FacebookSdk.setApplicationId("123456789")
    FacebookSdk.setClientToken("test-client-token")
    FacebookSdk.setAutoLogAppEventsEnabled(false)
    FacebookSdk.setAdvertiserIDCollectionEnabled(false)
    FacebookSdk.setExecutor(java.util.concurrent.Executor {})
    FacebookSdk.setTrackingEnabled(activity, true)
    FacebookSdk.sdkInitialize(activity, FacebookSdk.InitializeCallback {})
  }
  @After fun cleanup() { FacebookSdk.setTrackingEnabled(activity, false); activity.finish() }
  @Test fun deniedDialogCannotLoadWebContent() {
    val dialog = ProbeDialog(activity)
    FacebookSdk.setTrackingEnabled(activity, false)
    dialog.show()
    assertNull("Denied dialogs cannot create a transport", dialog.view())
    dialog.dismiss()
  }
  @Test fun withdrawalStopsWebViewAndRejectsEverySubresourceAfterRegrant() {
    FacebookSdk.setTrackingEnabled(activity, true)
    val dialog = ProbeDialog(activity)
    dialog.show()
    val view = requireNotNull(dialog.view())
    val client = shadowOf(view).webViewClient
    FacebookSdk.setTrackingEnabled(activity, false)
    FacebookSdk.setTrackingEnabled(activity, true)
    val blocked = client.shouldInterceptRequest(view, "https://m.facebook.com/tracking-pixel")
    assertNotNull("A stale web dialog cannot fetch subresources under a new grant", blocked)
    assertEquals(-1, blocked!!.data.read())
    shadowOf(android.os.Looper.getMainLooper()).idle()
    assertFalse(dialog.isShowing)
  }
}
