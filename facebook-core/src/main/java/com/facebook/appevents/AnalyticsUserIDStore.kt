/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.appevents

import com.facebook.internal.TrackingConsent

import android.preference.PreferenceManager
import android.util.Log
import com.facebook.FacebookSdk
import com.facebook.appevents.InternalAppEventsLogger.Companion.getAnalyticsExecutor
import com.facebook.appevents.internal.AppEventUtility.assertIsNotMainThread
import java.util.concurrent.locks.ReentrantReadWriteLock

internal object AnalyticsUserIDStore {
  private val TAG = AnalyticsUserIDStore::class.java.simpleName
  private const val ANALYTICS_USER_ID_KEY = "com.facebook.appevents.AnalyticsUserIDStore.userID"
  private val lock = ReentrantReadWriteLock()
  private var userID: String? = null

  @Volatile private var initialized = false
  @JvmStatic internal fun clearForConsent() { userID = null; initialized = false }
  @JvmStatic
  fun initStore() {
    if (!TrackingConsent.isEnabled()) return

    if (initialized) {
      return
    }
    getAnalyticsExecutor().execute { initAndWait() }
  }

  @JvmStatic
  fun setUserID(id: String?) {
    if (!TrackingConsent.isEnabled()) return

    assertIsNotMainThread()
    if (!initialized) {
      Log.w(TAG, "initStore should have been called before calling setUserID")
      initAndWait()
    }
    getAnalyticsExecutor().execute {
      TrackingConsent.update(Runnable {
      lock.writeLock().lock()
      try {
        userID = id
        val sharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(FacebookSdk.getApplicationContext())
        val editor = sharedPreferences.edit()
        editor.putString(ANALYTICS_USER_ID_KEY, userID)
        editor.apply()
      } finally {
        lock.writeLock().unlock()
      }
      })
    }
  }

  @JvmStatic
  fun getUserID(): String? {
    if (!TrackingConsent.isEnabled()) return null

    if (!initialized) {
      Log.w(TAG, "initStore should have been called before calling setUserID")
      initAndWait()
    }
    lock.readLock().lock()
    return try {
      userID
    } finally {
      lock.readLock().unlock()
    }
  }

  private fun initAndWait() {
    synchronized(TrackingConsent.lock) {
    if (!TrackingConsent.isEnabled()) return

    if (!TrackingConsent.isEnabled()) return

    if (initialized) {
      return
    }
    lock.writeLock().lock()
    try {
      if (initialized) {
        return
      }
      val sharedPreferences =
          PreferenceManager.getDefaultSharedPreferences(FacebookSdk.getApplicationContext())
      userID = sharedPreferences.getString(ANALYTICS_USER_ID_KEY, null)
      initialized = true
    } finally {
      lock.writeLock().unlock()
    }

    }
  }
}
