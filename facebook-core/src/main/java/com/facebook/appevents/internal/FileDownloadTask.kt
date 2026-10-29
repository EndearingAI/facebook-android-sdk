/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.appevents.internal

import com.facebook.internal.TrackingConsent

import android.os.AsyncTask
import androidx.annotation.VisibleForTesting
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class FileDownloadTask(
    private val uriStr: String,
    private val destFile: File,
    private val onSuccess: Callback
) : AsyncTask<String?, Void?, Boolean>() {

  private val consentGeneration = TrackingConsent.capture()

  @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
  public override fun doInBackground(vararg args: String?): Boolean {
    if (!TrackingConsent.isCurrent(consentGeneration)) return false
    try {
      return TrackingConsent.scope(consentGeneration) {
      val url = URL(uriStr)
      val conn = TrackingConsent.openConnection(url)
      val contentLength = conn.contentLength
      val stream = DataInputStream(conn.inputStream)
      val buffer = ByteArray(contentLength)
      stream.readFully(buffer)
      stream.close()
      synchronized(TrackingConsent.lock) {
      if (!TrackingConsent.isCurrent(consentGeneration)) return@scope false
      val fos = DataOutputStream(FileOutputStream(destFile))
      fos.write(buffer)
      fos.flush()
      fos.close()
      }
      true
      }
    } catch (e: Exception) {
      /* no op */
    }
    return false
  }

  override fun onPostExecute(isSuccess: Boolean) {
    if (isSuccess && TrackingConsent.isCurrent(consentGeneration)) {
      onSuccess.onComplete(destFile)
    }
  }

  fun interface Callback {
    fun onComplete(file: File)
  }
}
