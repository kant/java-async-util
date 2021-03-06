/*
* Copyright (c) IBM Corporation 2017. All Rights Reserved.
* Project name: java-async-util
* This project is licensed under the Apache License 2.0, see LICENSE.
*/

package com.ibm.asyncutil.locks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * An async wrapper over {@link Semaphore} for use in testing
 */
class SyncAsyncSemaphore implements AsyncSemaphore {
  private final Semaphore semaphore;
  private final Executor executor;

  public SyncAsyncSemaphore(final long permits, final boolean fair) {
    this(permits, fair, r -> new Thread(r).start());
  }

  public SyncAsyncSemaphore(final long permits, final boolean fair, final Executor executor) {
    this.semaphore = new Semaphore(Math.toIntExact(permits), fair);
    this.executor = executor;
  }

  @Override
  public CompletionStage<Void> acquire(final long permits) {
    if (permits < 0L) {
      throw new IllegalArgumentException();
    }
    final int p = Math.toIntExact(permits);

    final CompletableFuture<Void> future = new CompletableFuture<>();
    this.executor.execute(() -> {
      this.semaphore.acquireUninterruptibly(p);
      future.complete(null);
    });
    return future;
  }

  @Override
  public void release(final long permits) {
    if (permits < 0L) {
      throw new IllegalArgumentException();
    }
    this.semaphore.release(Math.toIntExact(permits));
  }

  @Override
  public boolean tryAcquire(final long permits) {
    if (permits < 0L) {
      throw new IllegalArgumentException();
    }
    return this.semaphore.tryAcquire(Math.toIntExact(permits));
  }

  @Override
  public long drainPermits() {
    return this.semaphore.drainPermits();
  }

  @Override
  public long getAvailablePermits() {
    return this.semaphore.availablePermits();
  }

  @Override
  public int getQueueLength() {
    return this.semaphore.getQueueLength();
  }

}

