/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under LICENSE.txt. */
package com.facebook.internal

import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Keeps scheduled work bound to the consent that accepted it, including periodic tasks. */
class ConsentScheduledExecutor(threads: Int) : ScheduledThreadPoolExecutor(threads) {
  private val work = CopyOnWriteArraySet<Future<*>>()
  init { removeOnCancelPolicy = true; TrackingConsent.register(this) }
  override fun execute(command: Runnable) {
    if (!TrackingConsent.isEnabled()) { if (command is Future<*>) command.cancel(false); return }
    schedule(command, 0, TimeUnit.NANOSECONDS)
  }
  override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> = synchronized(TrackingConsent.lock) {
    TrackingConsent.requireEnabled()
    track(super.schedule(TrackingConsent.wrap(command), delay, unit))
  }
  override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> = synchronized(TrackingConsent.lock) {
    TrackingConsent.requireEnabled()
    val generation = TrackingConsent.capture()
    track(super.schedule(Callable { TrackingConsent.scope(generation) { callable.call() } }, delay, unit))
  }
  override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> = synchronized(TrackingConsent.lock) {
    TrackingConsent.requireEnabled()
    track(super.scheduleAtFixedRate(TrackingConsent.wrap(command), initialDelay, period, unit))
  }
  override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> = synchronized(TrackingConsent.lock) {
    TrackingConsent.requireEnabled()
    track(super.scheduleWithFixedDelay(TrackingConsent.wrap(command), initialDelay, delay, unit))
  }
  private fun <V> track(future: ScheduledFuture<V>): ScheduledFuture<V> { work.filter { it.isDone }.forEach { work.remove(it) }; work.add(future); return future }
  override fun shutdown() { TrackingConsent.unregister(this); super.shutdown() }
  override fun shutdownNow(): MutableList<Runnable> { TrackingConsent.unregister(this); return super.shutdownNow() }
  internal fun cancelTrackingWork() { work.forEach { it.cancel(true) }; work.clear(); queue.clear() }
}
