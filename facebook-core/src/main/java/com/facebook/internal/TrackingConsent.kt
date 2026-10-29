/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under LICENSE.txt. */
package com.facebook.internal

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Process-local consent authority; SDK settings and remote responses cannot grant consent. */
object TrackingConsent {
  @JvmField val lock = Any()
  @Volatile private var enabled = false
  @Volatile private var generation = 0L
  private val workGeneration = ThreadLocal<Long?>()
  private val connections = CopyOnWriteArraySet<ConsentHttpURLConnection>()
  private val schedulers = CopyOnWriteArraySet<ConsentScheduledExecutor>()
  private val revocationCallbacks = CopyOnWriteArraySet<Runnable>()
  private val cancellationExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "facebook-consent-cancellation").apply { isDaemon = true }
  }

  @JvmStatic fun isEnabled(): Boolean {
    val work = workGeneration.get()
    return enabled && (work == null || work == generation)
  }
  @JvmStatic fun capture(): Long = synchronized(lock) { if (isEnabled()) generation else -1L }
  @JvmStatic fun isCurrent(captured: Long): Boolean = captured >= 0 && captured == generation && isEnabled()
  internal fun grant() = synchronized(lock) { if (!enabled) { generation++; enabled = true } }
  internal fun revoke() = synchronized(lock) {
    if (enabled) { generation++; enabled = false }
    schedulers.forEach { it.cancelTrackingWork() }
    connections.forEach { it.cancel() }
    connections.clear()
    val callbacks = revocationCallbacks.toList()
    revocationCallbacks.clear()
    callbacks.forEach { it.run() }
  }
  @JvmStatic fun requireEnabled() { if (!isEnabled()) throw CancellationException("Facebook tracking consent is not granted") }
  @JvmStatic fun update(work: Runnable) = synchronized(lock) { if (isEnabled()) work.run() }
  @JvmStatic fun wrap(work: Runnable): Runnable = wrap(capture(), work)
  @JvmStatic fun wrap(captured: Long, work: Runnable): Runnable = Runnable {
    if (!isCurrent(captured)) {
      if (work is Future<*>) work.cancel(false)
    } else {
      try { scope(captured) { work.run() } }
      catch (cancelled: CancellationException) { if (work is Future<*>) work.cancel(false) }
    }
  }
  fun <T> scope(captured: Long, work: () -> T): T {
    if (!isCurrent(captured)) throw CancellationException("Facebook tracking consent changed")
    val previous = workGeneration.get()
    workGeneration.set(captured)
    try { requireEnabled(); return work() }
    finally { if (previous == null) workGeneration.remove() else workGeneration.set(previous) }
  }
  @JvmStatic @Throws(IOException::class)
  fun openConnection(url: URL): HttpURLConnection = synchronized(lock) {
    if (!isEnabled()) throw IOException("Facebook tracking consent is not granted")
    val connection = ConsentHttpURLConnection(url, url.openConnection() as HttpURLConnection, capture())
    connections.add(connection)
    connection
  }
  @JvmStatic @Throws(IOException::class)
  fun adopt(connection: HttpURLConnection, captured: Long): HttpURLConnection = synchronized(lock) {
    if (!isCurrent(captured)) throw IOException("Facebook tracking consent changed")
    if (connection is ConsentHttpURLConnection) connection
    else ConsentHttpURLConnection(connection.url, connection, captured).also { connections.add(it) }
  }
  @JvmStatic fun registerRevocationCallback(captured: Long, callback: Runnable): Boolean = synchronized(lock) {
    if (!isCurrent(captured)) false else { revocationCallbacks.add(callback); true }
  }
  @JvmStatic fun unregisterRevocationCallback(callback: Runnable) { revocationCallbacks.remove(callback) }
  internal fun unregister(scheduler: ConsentScheduledExecutor) { schedulers.remove(scheduler) }
  internal fun register(scheduler: ConsentScheduledExecutor) { schedulers.add(scheduler) }
  @JvmStatic fun release(connection: ConsentHttpURLConnection) { connections.remove(connection) }
  @JvmStatic fun disconnectLater(connection: HttpURLConnection) { cancellationExecutor.execute { connection.disconnect() } }
}
