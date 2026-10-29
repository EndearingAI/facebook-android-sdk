/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under LICENSE.txt. */
package com.facebook.consent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/** Routes only provider HTTPS URLs to a real local HTTP recorder. */
public final class LoopbackHttpURLConnection extends HttpURLConnection {
  private final HttpURLConnection delegate;
  private boolean configured;

  public LoopbackHttpURLConnection(URL original) throws IOException {
    super(original);
    delegate = (HttpURLConnection) new URL(System.getProperty("com.facebook.consent.test.loopback")
        + original.getFile().replaceFirst("^/", "")).openConnection();
  }
  @Override public void connect() throws IOException {
    if (configured) return;
    configured = true;
    delegate.setRequestMethod(getRequestMethod());
    delegate.setDoInput(getDoInput());
    delegate.setDoOutput(getDoOutput());
    delegate.setConnectTimeout(getConnectTimeout());
    delegate.setReadTimeout(getReadTimeout());
    delegate.setInstanceFollowRedirects(getInstanceFollowRedirects());
    // Android permits a configured chunk size on GET; the JDK requires an output body.
    if (getDoOutput() && chunkLength > 0) delegate.setChunkedStreamingMode(chunkLength);
    for (Map.Entry<String, List<String>> entry : getRequestProperties().entrySet()) {
      for (String value : entry.getValue()) delegate.addRequestProperty(entry.getKey(), value);
    }
    delegate.connect();
  }
  @Override public InputStream getInputStream() throws IOException { connect(); return delegate.getInputStream(); }
  @Override public OutputStream getOutputStream() throws IOException { connect(); return delegate.getOutputStream(); }
  @Override public int getResponseCode() throws IOException { connect(); return delegate.getResponseCode(); }
  @Override public String getResponseMessage() throws IOException { connect(); return delegate.getResponseMessage(); }
  @Override public InputStream getErrorStream() { return delegate.getErrorStream(); }
  @Override public String getHeaderField(String key) { return delegate.getHeaderField(key); }
  @Override public String getHeaderField(int key) { return delegate.getHeaderField(key); }
  @Override public String getHeaderFieldKey(int key) { return delegate.getHeaderFieldKey(key); }
  @Override public Map<String, List<String>> getHeaderFields() { return delegate.getHeaderFields(); }
  @Override public void disconnect() { delegate.disconnect(); }
  @Override public boolean usingProxy() { return false; }
}
