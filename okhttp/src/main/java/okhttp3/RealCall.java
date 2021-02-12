/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nullable;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.cache.CacheInterceptor;
import okhttp3.internal.connection.ConnectInterceptor;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.BridgeInterceptor;
import okhttp3.internal.http.CallServerInterceptor;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http.RetryAndFollowUpInterceptor;
import okhttp3.internal.platform.Platform;
import okio.AsyncTimeout;
import okio.Timeout;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.platform.Platform.INFO;

final class RealCall implements Call, Comparable<RealCall> {
  final OkHttpClient client;
  final RetryAndFollowUpInterceptor retryAndFollowUpInterceptor;
  final AsyncTimeout timeout;

  /**
   * There is a cycle between the {@link Call} and {@link EventListener} that makes this awkward.
   * This will be set after we create the call instance then create the event listener instance.
   */
  private @Nullable EventListener eventListener;

  /** The application's original request unadulterated by redirects or auth headers. */
  Request originalRequest;
  final boolean forWebSocket;

  // Guarded by this.
  private boolean executed;

  private RealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
    this.client = client;
    this.originalRequest = originalRequest;
    this.forWebSocket = forWebSocket;
    this.retryAndFollowUpInterceptor = new RetryAndFollowUpInterceptor(client, forWebSocket);
    this.timeout = new AsyncTimeout() {
      @Override protected void timedOut() {
        cancel();
      }
    };
    if (client == null) {
      System.out.format("In Real Call. But client is null. URL is %s\n", this.originalRequest.url().toString());
    } else {
      System.out.format("In Real Call. and client is not null. URL is %s\n", this.originalRequest.url().toString());
      this.timeout.timeout(client.callTimeoutMillis(), MILLISECONDS);  
    }
    
  }

  static RealCall newRealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
    // Safely publish the Call instance to the EventListener.
    RealCall call = new RealCall(client, originalRequest, forWebSocket);
    System.out.format("MARAUDER: in newRealCall. Event listener is being created. Client in newRealCall is null (T/F):%s\n", client == null);
    call.eventListener = client.eventListenerFactory().create(call);
    call.eventListener.setClient(client);
    return call;
  }

  @Override public Request request() {
    return originalRequest;
  }

  @Override public Response execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    captureCallStackTrace();
    timeout.enter();
    eventListener.callStart(this);
    try {
      client.dispatcher().executed(this);
      Response result = getResponseWithInterceptorChain();
      if (result == null) throw new IOException("Canceled");
      return result;
    } catch (IOException e) {
      e = timeoutExit(e);
      eventListener.callFailed(this, e);
      throw e;
    } finally {
      client.dispatcher().finished(this);
    }
  }

  @Nullable IOException timeoutExit(@Nullable IOException cause) {
    if (!timeout.exit()) return cause;

    InterruptedIOException e = new InterruptedIOException("timeout");
    if (cause != null) {
      e.initCause(cause);
    }
    return e;
  }

  private void captureCallStackTrace() {
    Object callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()");
    retryAndFollowUpInterceptor.setCallStackTrace(callStackTrace);
  }

  @Override public void enqueue(Callback responseCallback) {
	  System.out.format("MARAUDER: [ImportantEvent] Going to enqueue %s\n", this.originalRequest.url().url().toString());
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    
    captureCallStackTrace();
    if (eventListener.getClient() == null && client != null) {
      eventListener.setClient(client);
    }
    eventListener.callStart(this);
    client.dispatcher().enqueue(new AsyncCall(responseCallback));
    
  }

  @Override public void cancel() {
    retryAndFollowUpInterceptor.cancel();
  }

  @Override public Timeout timeout() {
    return timeout;
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public boolean isCanceled() {
    return retryAndFollowUpInterceptor.isCanceled();
  }

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override public RealCall clone() {
    return RealCall.newRealCall(client, originalRequest, forWebSocket);
  }

  StreamAllocation streamAllocation() {
    return retryAndFollowUpInterceptor.streamAllocation();
  }

  final class AsyncCall extends NamedRunnable implements Comparable<AsyncCall> {
    private final Callback responseCallback;

    public boolean forMarauder; // is true if this request is from marauder. false if it is not a marauder request

    AsyncCall(String runnableName) {
      this.name = runnableName;
      this.responseCallback = null;
    }

    
    AsyncCall(Callback responseCallback) {
      super("OkHttp %s", redactedUrl());
      this.responseCallback = responseCallback;
    }

    String host() {
      return originalRequest.url().host();
    }

    Request request() {
      return originalRequest;
    }

    RealCall get() {
      return RealCall.this;
    }

    @Override
    public int compareTo(AsyncCall a2) {
      return this.get().compareTo(a2.get());
    }

    /**
     * Attempt to enqueue this async call on {@code executorService}. This will attempt to clean up
     * if the executor has been shut down by reporting the call as failed.
     */
    void executeOn(ExecutorService executorService) {
      assert (!Thread.holdsLock(client.dispatcher()));
      boolean success = false;
      this.forMarauder = false;
      try {
        executorService.execute(this);
        success = true;
      } catch (RejectedExecutionException e) {
        InterruptedIOException ioException = new InterruptedIOException("executor rejected");
        ioException.initCause(e);
        eventListener.callFailed(RealCall.this, ioException);
        if (responseCallback != null) {
          responseCallback.onFailure(RealCall.this, ioException);
        }
      } finally {
        if (!success) {
          client.dispatcher().finished(this); // This call is no longer running!
        }
      }
    }

    // here the call will be a GET. need to make it a head instead for non-text requests. 
    void executeOnForMarauder(ExecutorService executorService) {
      assert (!Thread.holdsLock(client.marauder()));
      boolean success = false;
      this.forMarauder = true;
      originalRequest.modifyMethodToHeadForMarauder();
      try {
        executorService.execute(this);
        success = true;
      } catch (RejectedExecutionException e) {
        InterruptedIOException ioException = new InterruptedIOException("executor rejected");
        ioException.initCause(e);
        eventListener.callFailed(RealCall.this, ioException);
        if (responseCallback != null) {
          responseCallback.onFailure(RealCall.this, ioException);
        }
      } finally {
        if (!success) {
          client.marauder().finished(this); // This call is no longer running!
        }
      }
    }

    @Override protected void execute() {
      boolean signalledCallback = false;
      timeout.enter();
      try {
        Response response = getResponseWithInterceptorChain();

        // only if this async req was not for marauder, we bother with
        // calling the response handler
        if (forMarauder == false) {
          signalledCallback = true;
          if (responseCallback != null) {
            responseCallback.onResponse(RealCall.this, response);
          }
        }
      } catch (IOException e) {
        e = timeoutExit(e);
        if (signalledCallback) {
          // Do not signal the callback twice!
          Platform.get().log(INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          eventListener.callFailed(RealCall.this, e);
          if (forMarauder == false) {
            if (responseCallback != null) {
              responseCallback.onFailure(RealCall.this, e);
            }
          }
          
        }
      } catch (Throwable t) {
        cancel();
        if (!signalledCallback) {
          IOException canceledException = new IOException("canceled due to " + t);
          if (forMarauder == false) {
            if (responseCallback != null) {
              responseCallback.onFailure(RealCall.this, canceledException);
            }
          }
        }
        throw t;
      } finally {
        if (forMarauder) {
          client.marauder().finished(this);
        } else {
          client.dispatcher().finished(this);
        }    
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  String toLoggableString() {
    return (isCanceled() ? "canceled " : "")
        + (forWebSocket ? "web socket" : "call")
        + " to " + redactedUrl();
  }

  String redactedUrl() {
    return originalRequest.url().redact();
  }

  Response getResponseWithInterceptorChain() throws IOException {
    // Build a full stack of interceptors.
    List<Interceptor> interceptors = new ArrayList<>();
    interceptors.addAll(client.interceptors());
    interceptors.add(retryAndFollowUpInterceptor);
    interceptors.add(new BridgeInterceptor(client.cookieJar()));
    interceptors.add(new CacheInterceptor(client.internalCache()));
    interceptors.add(new ConnectInterceptor(client));
    if (!forWebSocket) {
      interceptors.addAll(client.networkInterceptors());
    }
    interceptors.add(new CallServerInterceptor(forWebSocket));

    Interceptor.Chain chain = new RealInterceptorChain(interceptors, null, null, null, 0,
        originalRequest, this, eventListener, client.connectTimeoutMillis(),
        client.readTimeoutMillis(), client.writeTimeoutMillis());

    Response response = chain.proceed(originalRequest);
    if (retryAndFollowUpInterceptor.isCanceled()) {
      closeQuietly(response);
      throw new IOException("Canceled");
    }
    return response;
  }


  /**
   * MARAUDER STUFF
   */ 
  public long instantAtWhichToRefresh;
  
  public long timeLeftForMeToBeRefreshedInSeconds() {
      long milliseconds_now = System.currentTimeMillis();
      long seconds_now = milliseconds_now / 1000;
      return instantAtWhichToRefresh - seconds_now;
  }


  @Override public boolean readyToExecute() {
      long milliseconds_now = System.currentTimeMillis();
      long seconds_now = milliseconds_now / 1000;
      if (seconds_now > instantAtWhichToRefresh) {
        // System.out.format("MARAUDER: it is time to refresh. current: %d, refresh time: %d. url is %s.\n", seconds_now, instantAtWhichToRefresh, this.originalRequest.url().toString());
        return true;
      } else {
        // System.out.format("MARAUDER: it is not yet time to refresh. current: %d, refresh time: %d. url is %s. \n", seconds_now, instantAtWhichToRefresh, this.originalRequest.url().toString());
        return false;
      }
  }

  public AsyncCall getAsAsyncCall() {
    return new AsyncCall("MARAUDER");
  }
  public void setExpiringMaxAge(int maxAge) {
    long milliseconds_now = System.currentTimeMillis();
    long seconds_now = milliseconds_now / 1000;
    long instantAtWhichToRefresh = seconds_now + maxAge;
    this.instantAtWhichToRefresh = instantAtWhichToRefresh;
  }
  @Override
  public int compareTo(RealCall p2) {
    RealCall p1 = this;
    return p1.instantAtWhichToRefresh > p2.instantAtWhichToRefresh ? -1 : p1.instantAtWhichToRefresh < p2.instantAtWhichToRefresh ? +1 : 0;
  }
}
