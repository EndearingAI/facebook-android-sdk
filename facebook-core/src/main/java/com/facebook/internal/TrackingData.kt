/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under LICENSE.txt. */
package com.facebook.internal

import android.content.Context
import android.preference.PreferenceManager
import com.facebook.AccessTokenManager
import com.facebook.AuthenticationTokenManager
import com.facebook.ProfileManager
import com.facebook.UserSettingsManager
import com.facebook.appevents.AnalyticsUserIDStore
import com.facebook.appevents.AppEventQueue
import com.facebook.appevents.AppEventsLoggerImpl
import com.facebook.appevents.UserDataStore
import com.facebook.appevents.internal.ActivityLifecycleTracker
import com.facebook.appevents.cloudbridge.AppEventsConversionsAPITransformerWebRequests
import java.io.File

/** SDK-owned storage is erased synchronously; a failed erase keeps the SDK disabled. */
internal object TrackingData {
  fun clear(context: Context) {
    val failure = IllegalStateException("Unable to erase Facebook tracking data; keep consent disabled and retry cleanup")
    val actions = listOf<() -> Unit>(
      { com.facebook.appevents.codeless.CodelessManager.clearForConsent() },
      { com.facebook.internal.instrument.crashreport.CrashHandler.clearForConsent() },
      { AppEventQueue.clearForConsent() },
      { AppEventsLoggerImpl.clearForConsent() },
      { AnalyticsUserIDStore.clearForConsent() },
      { UserDataStore.clearForConsent() },
      { AttributionIdentifiers.clearForConsent() },
      { InstallReferrerUtil.clearForConsent() },
      { ActivityLifecycleTracker.clearForConsent() },
      { FetchedAppSettingsManager.clearForConsent() },
      { FetchedAppGateKeepersManager.clearForConsent() },
      { UserSettingsManager.clearForConsent() },
      { AccessTokenManager.clearForConsent() },
      { AuthenticationTokenManager.clearForConsent() },
      { ProfileManager.clearForConsent() },
      { ImageDownloader.clearForConsent() },
      { AppEventsConversionsAPITransformerWebRequests.clearForConsent() },
      { erase(context.getFileStreamPath("AppEventsLogger.persistedevents")) },
      { erasePreferences(context) },
      { context.cacheDir.listFiles()?.filter { file ->
          file.name == "instrument" || file.name == "facebook_ml" || file.name == "ImageResponseCache" || file.name == "UrlRedirectCache" || file.name.startsWith("com.facebook")
        }?.forEach { erase(it) } }
    )
    actions.forEach { action -> try { action() } catch (error: RuntimeException) { failure.addSuppressed(error) } }
    if (failure.suppressed.isNotEmpty()) throw failure
  }
  private fun erase(file: File) {
    if (!file.exists()) return
    file.listFiles()?.forEach { erase(it) }
    if (!file.delete()) throw IllegalStateException("Unable to erase Facebook tracking file ${file.name}")
  }
  private fun erasePreferences(context: Context) {
    val names = File(context.applicationInfo.dataDir, "shared_prefs").listFiles()
      ?.mapNotNull { file -> when {
        file.name.endsWith(".xml.bak") -> file.name.removeSuffix(".xml.bak")
        file.name.endsWith(".xml") -> file.name.removeSuffix(".xml")
        else -> null
      } }.orEmpty().toMutableSet()
    names.addAll(listOf("com.facebook.sdk.appEventPreferences", "com.facebook.sdk.attributionTracking", "com.facebook.AccessTokenManager.SharedPreferences", "com.facebook.AuthenticationTokenManager.SharedPreferences", "com.facebook.sdk.USER_SETTINGS", "com.facebook.internal.preferences.APP_SETTINGS", "com.facebook.internal.preferences.APP_GATEKEEPERS"))
    names.filter { it.startsWith("com.facebook") || it.startsWith("com.meta") }.forEach { name ->
      val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
      val editor = preferences.edit()
      preferences.all.forEach { (key, value) ->
        // Provider install-once bookkeeping contains an application ID and timestamp, not an identity.
        if (!(name == "com.facebook.sdk.attributionTracking" && key.endsWith("ping") && value is Long)) editor.remove(key)
      }
      if (!editor.commit()) throw IllegalStateException("Unable to erase Facebook tracking preferences $name")
    }
    val defaults = PreferenceManager.getDefaultSharedPreferences(context)
    val editor = defaults.edit()
    defaults.all.keys.filter { it.startsWith("com.facebook.") || it.startsWith("com.meta.") }.forEach { editor.remove(it) }
    if (!editor.commit()) throw IllegalStateException("Unable to erase Facebook tracking identity preferences")
  }
}
