/*
 * Copyright (C) 2013 Square, Inc.
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

import java.nio.channels.AsynchronousChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.RealCall.AsyncCall;
import okhttp3.internal.Util;


import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
/**
 * Policy on when async requests are executed.
 *
 * <p>Each Marauder uses an {@link ExecutorService} to run calls internally. If you supply your
 * own executor, it should be able to run {@linkplain #getMaxRequests the configured maximum} number
 * of calls concurrently.
 */
public final class Marauder {
  private int maxRequests = 64;
  private int maxRequestsPerHost = 5;
  private @Nullable Runnable idleCallback;

  private @Nullable ScheduledThreadPoolExecutor scheduledExecutorService; // this is the workers
  private @Nullable ScheduledThreadPoolExecutor scheduledURLParsingExecutorService; // this is the workers
  private @Nullable ScheduledFuture scheduledFunction; // the function to be called after X time will be stored here
  private @Nullable ScheduledFuture scheduledFunctionForURLParsing; // the function for URLParsing to be called after X time will be stored here

  /** Executes calls. Created lazily. */
  private @Nullable ExecutorService executorService;

  /** Ready async calls in the order they'll be run. */
  private final PriorityBlockingQueue<AsyncCall> readyAsyncCalls = new PriorityBlockingQueue<>();

  /** Running asynchronous calls. Includes canceled calls that haven't finished yet. */
  private final Deque<AsyncCall> runningAsyncCalls = new ArrayDeque<>();

  /** Running synchronous calls. Includes canceled calls that haven't finished yet. */
  private final Deque<RealCall> runningSyncCalls = new ArrayDeque<>();

  private final Set<String> pendingParents = Collections.synchronizedSet(new HashSet<String>()); // set of parents we have to process

  /** Parent to parent-body mappings for textual resources */
  private final ConcurrentHashMap<String, String> parentToParentBodyMap = new ConcurrentHashMap<>();

  /** Parent to parent request mapping. For retrieving the headers */
  private final ConcurrentHashMap<String, Request> parentToParentRequestMap = new ConcurrentHashMap<>();

  /** Parent to child URL mapping */
  private final ConcurrentHashMap<String, List<String>> parentToChildURLsMap = new ConcurrentHashMap<>();


  public Marauder(ExecutorService executorService) {
    System.out.println("MARAUDER: initializing with executor service");
    this.scheduledExecutorService = new ScheduledThreadPoolExecutor(1); // only one thread is enough as we only schedule promoteAndExecute function
    this.executorService = executorService;
    this.scheduledURLParsingExecutorService = new ScheduledThreadPoolExecutor(1); // parse 1 url at a time
  }

  public Marauder() {
    System.out.println("MARAUDER: initializing in empty constructor");
    this.scheduledExecutorService = new ScheduledThreadPoolExecutor(1); // only one thread is enough as we only schedule promoteAndExecute function
    this.scheduledURLParsingExecutorService = new ScheduledThreadPoolExecutor(1);
  }

  public synchronized ExecutorService executorService() {
    if (executorService == null) {
      executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
          new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Marauder", false));
    }
    return executorService;
  }

  /**
   * Set the maximum number of requests to execute concurrently. Above this requests queue in
   * memory, waiting for the running calls to complete.
   *
   * <p>If more than {@code maxRequests} requests are in flight when this is invoked, those requests
   * will remain in flight.
   */
  public void setMaxRequests(int maxRequests) {
    if (maxRequests < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequests);
    }
    synchronized (this) {
      this.maxRequests = maxRequests;
    }
    promoteAndExecute();
  }

  public synchronized int getMaxRequests() {
    return maxRequests;
  }

  /**
   * Set the maximum number of requests for each host to execute concurrently. This limits requests
   * by the URL's host name. Note that concurrent requests to a single IP address may still exceed
   * this limit: multiple hostnames may share an IP address or be routed through the same HTTP
   * proxy.
   *
   * <p>If more than {@code maxRequestsPerHost} requests are in flight when this is invoked, those
   * requests will remain in flight.
   *
   * <p>WebSocket connections to hosts <b>do not</b> count against this limit.
   */
  public void setMaxRequestsPerHost(int maxRequestsPerHost) {
    if (maxRequestsPerHost < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
    }
    synchronized (this) {
      this.maxRequestsPerHost = maxRequestsPerHost;
    }
    promoteAndExecute();
  }

  public synchronized int getMaxRequestsPerHost() {
    return maxRequestsPerHost;
  }

  /**
   * Set a callback to be invoked each time the Marauder becomes idle (when the number of running
   * calls returns to zero).
   *
   * <p>Note: The time at which a {@linkplain Call call} is considered idle is different depending
   * on whether it was run {@linkplain Call#enqueue(Callback) asynchronously} or
   * {@linkplain Call#execute() synchronously}. Asynchronous calls become idle after the
   * {@link Callback#onResponse onResponse} or {@link Callback#onFailure onFailure} callback has
   * returned. Synchronous calls become idle once {@link Call#execute() execute()} returns. This
   * means that if you are doing synchronous calls the network layer will not truly be idle until
   * every returned {@link Response} has been closed.
   */
  public synchronized void setIdleCallback(@Nullable Runnable idleCallback) {
    this.idleCallback = idleCallback;
  }

  void enqueue(AsyncCall call) {
    synchronized (this) {
      readyAsyncCalls.add(call);
    }
    promoteAndExecute();
  }


  void addParentToSet(String parentURL, String parentBody, Request originalRequestInfo) {
    pendingParents.add(parentURL);
    parentToParentBodyMap.put(parentURL, parentBody);
    parentToParentRequestMap.put(parentURL, originalRequestInfo);
    parentToChildURLsMap.put(parentURL, Collections.synchronizedList(new ArrayList<String>()));
    System.out.format("MARAUDER: number of parents encountered so far is %d\n", parentToChildURLsMap.size());
    System.out.format("MARAUDER: parent body is %s\n.", parentBody.substring(0, 50));
    
    this.scheduledFunctionForURLParsing = this.scheduledURLParsingExecutorService.schedule(new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
          return parseOneParentURL();
      }
  }, 5, TimeUnit.SECONDS); // call parseOneParentURL after N second
  }


  /**
   * Cancel all calls currently enqueued or executing. Includes calls executed both {@linkplain
   * Call#execute() synchronously} and {@linkplain Call#enqueue asynchronously}.
   */
  public synchronized void cancelAll() {
    for (AsyncCall call : readyAsyncCalls) {
      call.get().cancel();
    }

    for (AsyncCall call : runningAsyncCalls) {
      call.get().cancel();
    }

    for (RealCall call : runningSyncCalls) {
      call.cancel();
    }
  }

    /**
   * Returns a list with all links contained in the input
   */
  // from https://stackoverflow.com/questions/5713558/detect-and-extract-url-from-a-string
  public static List<String> extractUrls(String text)
  {
      List<String> containedUrls = Collections.synchronizedList(new ArrayList<String>());
      String urlRegex = "((https?|ftp|gopher|telnet|file):((//)|(\\\\))+[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]*)";
      Pattern pattern = Pattern.compile(urlRegex, Pattern.CASE_INSENSITIVE);
      Matcher urlMatcher = pattern.matcher(text);

      while (urlMatcher.find())
      {
          containedUrls.add(text.substring(urlMatcher.start(0),
                  urlMatcher.end(0)));
      }

      return containedUrls;
  }

  private Boolean parseOneParentURL() {
    System.out.format("MARAUDER: Inside parseOneParentURL\n");
    // return true;
    if (pendingParents.size() <= 0) return false;
    else {
      Iterator<String> it = pendingParents.iterator();
      while(it.hasNext()) {
        System.out.format("MARAUDER: inside while loop at time %d\n", System.currentTimeMillis() / 1000);
        String parentURL = it.next();
        pendingParents.remove(parentURL);
        System.out.format("MARAUDER: going to parse parent URL %s\n", parentURL);
        String parentBody = this.parentToParentBodyMap.get(parentURL);

        parentToChildURLsMap.get(parentURL).addAll(extractUrls(parentBody));
        System.out.format("MARAUDER: for this parent URL, we have %d children with the first child URL being %s\n", parentToChildURLsMap.get(parentURL).size(), parentToChildURLsMap.get(parentURL).size() > 0 ? parentToChildURLsMap.get(parentURL).get(0) : "NONE");
      }
      return true;
    }
  }

  /**
   * Promotes eligible calls from {@link #readyAsyncCalls} to {@link #runningAsyncCalls} and runs
   * them on the executor service. Must not be called with synchronization because executing calls
   * can call into user code.
   *
   * @return true if the Marauder is currently running calls.
   */
  private boolean promoteAndExecute() {
    

    System.out.println("MARAUDER: promoteAndExecute is Running");
    assert (!Thread.holdsLock(this));

    List<AsyncCall> executableCalls = new ArrayList<>();
    boolean isRunning;
    long timeTillIShouldWakeUpNext = 10; // set to wake up once in 10 seconds for now// Long.MAX_VALUE; // wake up once every Long.MAX_VALUE seconds by default. make this something like 10 or 30 seconds for testing so we know it works as expected.
    synchronized (this) {
      if (this.scheduledFunction != null) {
        scheduledFunction.cancel(true);
        scheduledFunction = null;
      }
      long milliseconds_now = System.currentTimeMillis();
      long seconds_now = milliseconds_now / 1000;
      System.out.format("MARAUDER: number of ready calls is %d\n", readyAsyncCalls.size());
      System.out.format("MARAUDER: item at front of queue of ready calls is %s, it expires at %d, time now is %d\n", readyAsyncCalls.peek().get().request().url().url().toString(), readyAsyncCalls.peek().get().instantAtWhichToRefresh, seconds_now);
      AsyncCall asyncCall = readyAsyncCalls.peek();
      if (runningAsyncCalls.size() < maxRequests) { // Max capacity.
        if (runningCallsForHost(asyncCall) < maxRequestsPerHost) {// Host max capacity.
          if (readyAsyncCalls.peek().get().readyToExecute()) {
            asyncCall = readyAsyncCalls.remove(); // technically we have the same item through the peek so wwe do not need to store the item we remove
            System.out.format("MARAUDER: [ImportantEvent] Going to refresh %s\n", asyncCall.get().request().url().url().toString());
            executableCalls.add(asyncCall);
            runningAsyncCalls.add(asyncCall); // here i should actually send the conditional HEAD
          }
        }
      }
      
      
      // for (Iterator<AsyncCall> i = readyAsyncCalls.iterator(); i.hasNext(); ) { // MARAUDER: change this to a priority queue and only retrieve the first element
      //   AsyncCall asyncCall = i.next();

      //   if (runningAsyncCalls.size() >= maxRequests) break; // Max capacity.
      //   if (runningCallsForHost(asyncCall) >= maxRequestsPerHost) continue; // Host max capacity.
      //   if (asyncCall.get().readyToExecute()) {
      //     i.remove();
      //     System.out.format("MARAUDER: [ImportantEvent] Going to refresh %s\n", asyncCall.get().request().url().url().toString());
      //     executableCalls.add(asyncCall);
      //     runningAsyncCalls.add(asyncCall); // here i should actually send the conditional HEAD
      //   } else {
      //     long numberOfSecondsUntillThisAssetShouldBeRefreshed = asyncCall.get().timeLeftForMeToBeRefreshedInSeconds();
      //     if (numberOfSecondsUntillThisAssetShouldBeRefreshed < timeTillIShouldWakeUpNext && numberOfSecondsUntillThisAssetShouldBeRefreshed > 0) {
      //       timeTillIShouldWakeUpNext = numberOfSecondsUntillThisAssetShouldBeRefreshed;
      //     } else if (numberOfSecondsUntillThisAssetShouldBeRefreshed == 0) {
      //       timeTillIShouldWakeUpNext = 1;
      //     }
      //   }
      // }
      isRunning = runningCallsCount() > 0;
    }

    for (int i = 0, size = executableCalls.size(); i < size; i++) {
      AsyncCall asyncCall = executableCalls.get(i);
      asyncCall.executeOnForMarauder(executorService());
    }

    if (readyAsyncCalls.size() > 0) {
      System.out.format("MARAUDER: going to wake up in %d seconds as next asset is expiring then\n", timeTillIShouldWakeUpNext);
      this.scheduledFunction = this.scheduledExecutorService.schedule(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return promoteAndExecute();
            }
        }, timeTillIShouldWakeUpNext, TimeUnit.SECONDS);
    } else {
      System.out.format("MARAUDER: not going to wake up in as there is no queued request\n");
    }
    return isRunning;
  }

  /** Returns the number of running calls that share a host with {@code call}. */
  private int runningCallsForHost(AsyncCall call) {
    int result = 0;
    for (AsyncCall c : runningAsyncCalls) {
      if (c.get().forWebSocket) continue;
      if (c.host().equals(call.host())) result++;
    }
    return result;
  }

  /** Used by {@code Call#execute} to signal it is in-flight. */
  synchronized void executed(RealCall call) {
    runningSyncCalls.add(call);
  }

  /** Used by {@code AsyncCall#run} to signal completion. */
  void finished(AsyncCall call) {
    finished(runningAsyncCalls, call);
  }

  /** Used by {@code Call#execute} to signal completion. */
  void finished(RealCall call) {
    finished(runningSyncCalls, call);
  }

  private <T> void finished(Deque<T> calls, T call) {
    Runnable idleCallback;
    synchronized (this) {
      if (!calls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
      idleCallback = this.idleCallback;
    }

    boolean isRunning = true; // promoteAndExecute(); MARAUDER:no need to call promote and execute here as we have a countdown loop which anyway calls marauder again and again

    if (!isRunning && idleCallback != null) {
      idleCallback.run();
    }
  }

  /** Returns a snapshot of the calls currently awaiting execution. */
  public synchronized List<Call> queuedCalls() {
    List<Call> result = new ArrayList<>();
    for (AsyncCall asyncCall : readyAsyncCalls) {
      result.add(asyncCall.get());
    }
    return Collections.unmodifiableList(result);
  }

  /** Returns a snapshot of the calls currently being executed. */
  public synchronized List<Call> runningCalls() {
    List<Call> result = new ArrayList<>();
    result.addAll(runningSyncCalls);
    for (AsyncCall asyncCall : runningAsyncCalls) {
      result.add(asyncCall.get());
    }
    return Collections.unmodifiableList(result);
  }

  /** Returns a hashset. The hashset contains all the URLs as strings that are currently in flight due to marauder. */
  public synchronized HashSet<String> runningCallsAsURLs() {
    HashSet<String> setOfURLsInFlight = new HashSet<String>(); 
    for (Call c : runningSyncCalls) {
      setOfURLsInFlight.add(c.request().url().url().toString());
    }
    for (AsyncCall c : runningAsyncCalls) {
      setOfURLsInFlight.add(c.get().request().url().url().toString());
    }
    return setOfURLsInFlight;
  }

  public synchronized int queuedCallsCount() {
    return readyAsyncCalls.size();
  }

  public synchronized int runningCallsCount() {
    return runningAsyncCalls.size() + runningSyncCalls.size();
  }
}
