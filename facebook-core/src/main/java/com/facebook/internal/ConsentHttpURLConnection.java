/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under LICENSE.txt. */
package com.facebook.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/** Gates implicit HTTP starts as well as explicit connect calls and disables hidden redirects. */
public final class ConsentHttpURLConnection extends HttpURLConnection {
  private final HttpURLConnection delegate;
  private final long generation;
  private volatile boolean cancelled;
  private boolean started;

  ConsentHttpURLConnection(URL url, HttpURLConnection delegate, long generation) {
    super(url);
    this.delegate = delegate;
    this.generation = generation;
    delegate.setInstanceFollowRedirects(false);
  }
  private void checkConsent() throws IOException {
    if (cancelled || !TrackingConsent.isCurrent(generation)) throw new IOException("Facebook tracking consent changed");
  }
  @Override public void connect() throws IOException {
    synchronized (TrackingConsent.lock) {
      checkConsent();
      if (started) return;
      started = true;
    }
    // A socket connect can block. Withdrawal must close the gate without waiting for it.
    checkConsent();
    try { delegate.connect(); checkConsent(); }
    finally { if (cancelled) TrackingConsent.disconnectLater(delegate); }
  }
  public void cancel() { cancelled = true; TrackingConsent.disconnectLater(delegate); }
  @Override public void disconnect() { cancelled = true; TrackingConsent.release(this); TrackingConsent.disconnectLater(delegate); }
  @Override public boolean usingProxy() { return delegate.usingProxy(); }
  @Override public InputStream getInputStream() throws IOException {
    connect(); checkConsent(); InputStream stream = delegate.getInputStream(); checkConsent(); return guard(stream);
  }
  @Override public OutputStream getOutputStream() throws IOException {
    connect(); checkConsent(); OutputStream stream = delegate.getOutputStream(); checkConsent();
    return new FilterOutputStream(stream) {
      @Override public void write(int value) throws IOException { checkConsent(); out.write(value); checkConsent(); }
      @Override public void write(byte[] bytes, int offset, int length) throws IOException { checkConsent(); out.write(bytes, offset, length); checkConsent(); }
      @Override public void flush() throws IOException { checkConsent(); out.flush(); checkConsent(); }
      @Override public void close() throws IOException { checkConsent(); out.close(); }
    };
  }
  private InputStream guard(InputStream stream) {
    if (stream == null) return null;
    return new FilterInputStream(stream) {
      @Override public int read() throws IOException { checkConsent(); int value = in.read(); checkConsent(); return value; }
      @Override public int read(byte[] bytes, int offset, int length) throws IOException { checkConsent(); int size = in.read(bytes, offset, length); checkConsent(); return size; }
      @Override public long skip(long count) throws IOException { checkConsent(); long size = in.skip(count); checkConsent(); return size; }
      @Override public int available() throws IOException { checkConsent(); return in.available(); }
      @Override public synchronized void reset() throws IOException { checkConsent(); in.reset(); }
    };
  }
  @Override public InputStream getErrorStream() { try { connect(); checkConsent(); return guard(delegate.getErrorStream()); } catch (IOException denied) { return null; } }
  @Override public int getResponseCode() throws IOException { connect(); checkConsent(); int code = delegate.getResponseCode(); checkConsent(); return code; }
  @Override public String getResponseMessage() throws IOException { connect(); checkConsent(); String message = delegate.getResponseMessage(); checkConsent(); return message; }
  @Override public String getHeaderField(String key) { try { connect(); return delegate.getHeaderField(key); } catch (IOException denied) { return null; } }
  @Override public String getHeaderField(int key) { try { connect(); return delegate.getHeaderField(key); } catch (IOException denied) { return null; } }
  @Override public String getHeaderFieldKey(int key) { try { connect(); return delegate.getHeaderFieldKey(key); } catch (IOException denied) { return null; } }
  @Override public Map<String,List<String>> getHeaderFields() { try { connect(); return delegate.getHeaderFields(); } catch (IOException denied) { return java.util.Collections.emptyMap(); } }
  @Override public void setRequestMethod(String method) throws java.net.ProtocolException { delegate.setRequestMethod(method); }
  @Override public String getRequestMethod() { return delegate.getRequestMethod(); }
  @Override public void setRequestProperty(String key, String value) { delegate.setRequestProperty(key, value); }
  @Override public void addRequestProperty(String key, String value) { delegate.addRequestProperty(key, value); }
  @Override public String getRequestProperty(String key) { return delegate.getRequestProperty(key); }
  @Override public Map<String,List<String>> getRequestProperties() { return delegate.getRequestProperties(); }
  @Override public void setDoInput(boolean value) { delegate.setDoInput(value); }
  @Override public boolean getDoInput() { return delegate.getDoInput(); }
  @Override public void setDoOutput(boolean value) { delegate.setDoOutput(value); }
  @Override public boolean getDoOutput() { return delegate.getDoOutput(); }
  @Override public void setUseCaches(boolean value) { delegate.setUseCaches(value); }
  @Override public boolean getUseCaches() { return delegate.getUseCaches(); }
  @Override public void setConnectTimeout(int value) { delegate.setConnectTimeout(value); }
  @Override public int getConnectTimeout() { return delegate.getConnectTimeout(); }
  @Override public void setReadTimeout(int value) { delegate.setReadTimeout(value); }
  @Override public int getReadTimeout() { return delegate.getReadTimeout(); }
  @Override public void setChunkedStreamingMode(int value) { delegate.setChunkedStreamingMode(value); }
  @Override public void setFixedLengthStreamingMode(int value) { delegate.setFixedLengthStreamingMode(value); }
  @Override public void setFixedLengthStreamingMode(long value) { delegate.setFixedLengthStreamingMode(value); }
  @Override public void setInstanceFollowRedirects(boolean value) { delegate.setInstanceFollowRedirects(false); }
  @Override public boolean getInstanceFollowRedirects() { return false; }
  @Override public void setIfModifiedSince(long value) { delegate.setIfModifiedSince(value); }
  @Override public long getIfModifiedSince() { return delegate.getIfModifiedSince(); }
  @Override public void setAllowUserInteraction(boolean value) { delegate.setAllowUserInteraction(value); }
  @Override public boolean getAllowUserInteraction() { return delegate.getAllowUserInteraction(); }
}
