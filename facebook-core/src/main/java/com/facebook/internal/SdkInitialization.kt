/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under LICENSE.txt. */
package com.facebook.internal

import com.facebook.FacebookException
import com.facebook.FacebookSdk
import java.util.ArrayDeque

internal class SdkInitialization(private val generation: Long) {
  private val callbacks = ArrayDeque<FacebookSdk.InitializeCallback>()
  private var finished = false
  fun add(callback: FacebookSdk.InitializeCallback?) { if (callback != null) callbacks.add(callback) }
  fun succeed() = synchronized(TrackingConsent.lock) {
    if (finished) return@synchronized
    if (!TrackingConsent.isCurrent(generation)) { cancel(); return@synchronized }
    var failure: RuntimeException? = null
    while (callbacks.isNotEmpty() && !finished) {
      if (!TrackingConsent.isCurrent(generation)) { cancel(); break }
      try { callbacks.removeFirst().onInitialized() }
      catch (callbackError: RuntimeException) {
        if (failure == null) failure = IllegalStateException("Facebook initialization callback failed", callbackError)
        else failure.addSuppressed(callbackError)
      }
    }
    finished = true
    failure?.let { throw it }
  }
  fun cancel() = fail(FacebookException("Tracking consent was withdrawn before Facebook SDK initialization completed"))
  fun fail(error: FacebookException) = synchronized(TrackingConsent.lock) {
    if (finished) return@synchronized
    finished = true
    var failure: RuntimeException? = null
    while (callbacks.isNotEmpty()) {
      try { reject(callbacks.removeFirst(), error) }
      catch (callbackError: RuntimeException) {
        if (failure == null) failure = IllegalStateException("Facebook initialization callback failed", callbackError)
        else failure.addSuppressed(callbackError)
      }
    }
    failure?.let { throw it }
  }
  companion object {
    fun reject(callback: FacebookSdk.InitializeCallback?, error: FacebookException) {
      when (callback) {
        is FacebookSdk.TrackingInitializeCallback -> callback.onInitializationFailed(error)
        null -> throw error
        else -> callback.onInitialized()
      }
    }
  }
}
