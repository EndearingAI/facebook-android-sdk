/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under LICENSE.txt. */
package com.facebook

import com.facebook.internal.TrackingConsent

/** One execution owns its callbacks until delivery, including time spent on a Handler queue. */
internal class GraphRequestCompletion(
    private val batch: GraphRequestBatch,
    private val cancelTask: (() -> Unit)? = null
) {
  private val requests = batch.requests.toList()
  private val generation = requests.firstOrNull()?.consentGeneration ?: -1L
  private val callbacks = requests.map { it.callback }.toMutableList()
  private val batchCallbacks = batch.callbacks.toMutableList()
  private val revoked = Runnable { cancel() }
  private var pendingResponses: List<GraphResponse>? = null
  private var scheduled = false
  private var finished = false
  private var cancelled = false
  private var delivering = false

  init {
    synchronized(TrackingConsent.lock) {
      if (!hasCurrentConsent() || !TrackingConsent.registerRevocationCallback(generation, revoked)) cancel()
    }
  }

  private fun hasCurrentConsent(): Boolean =
      requests.isNotEmpty() && requests.all { it.consentGeneration == generation } && TrackingConsent.isCurrent(generation)

  fun complete(responses: List<GraphResponse>) = synchronized(TrackingConsent.lock) {
    if (cancelled) {
      responses.forEach { it.clearForConsent() }
      return@synchronized
    }
    if (finished) return@synchronized
    if (!hasCurrentConsent()) {
      responses.forEach { it.clearForConsent() }
      cancel()
      return@synchronized
    }
    pendingResponses = responses
    scheduleDelivery()
  }

  fun cancel() = synchronized(TrackingConsent.lock) {
    if (finished || cancelled) return@synchronized
    cancelled = true
    requests.forEach { it.clearForConsent() }
    batch.batchApplicationId = null
    batch.callbacks.clear()
    pendingResponses?.forEach { it.clearForConsent() }
    pendingResponses = null
    cancelTask?.invoke()
    if (!delivering) scheduleDelivery()
  }

  fun wasCancelled(): Boolean = synchronized(TrackingConsent.lock) { cancelled }

  fun cancellationResponses(): List<GraphResponse> = requests.map { cancellationResponse(it) }

  private fun cancellationResponse(request: GraphRequest): GraphResponse =
      GraphResponse(request, null, FacebookRequestError(null, FacebookException("Facebook tracking consent changed")))

  private fun scheduleDelivery() {
    if (scheduled) return
    scheduled = true
    // Cancellation must settle even when the consent-aware executor can no longer accept work.
    val delivery = Runnable { deliver() }
    batch.callbackHandler?.post(delivery) ?: delivery.run()
  }

  private fun deliver() = synchronized(TrackingConsent.lock) {
    if (finished || delivering) return@synchronized
    delivering = true
    var failure: RuntimeException? = null
    fun notifyCaller(callback: () -> Unit) {
      try {
        if (cancelled) TrackingConsent.cancellationScope(callback)
        else TrackingConsent.scope(generation, callback)
      } catch (error: RuntimeException) {
        if (failure == null) failure = IllegalStateException("Facebook Graph callback failed", error)
        else failure?.addSuppressed(error)
      }
    }
    try {
      callbacks.indices.forEach { index ->
        if (!hasCurrentConsent()) cancel()
        val callback = callbacks[index]
        callbacks[index] = null
        if (callback != null) {
          val response = if (cancelled) cancellationResponse(requests[index])
              else checkNotNull(pendingResponses)[index]
          notifyCaller { callback.onCompleted(response) }
        }
      }
      while (batchCallbacks.isNotEmpty()) {
        if (!hasCurrentConsent()) cancel()
        val callback = batchCallbacks.removeAt(0)
        notifyCaller { callback.onBatchCompleted(batch) }
      }
    } finally {
      pendingResponses = null
      finished = true
      delivering = false
      TrackingConsent.unregisterRevocationCallback(revoked)
    }
    failure?.let { throw it }
  }
}
