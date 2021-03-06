/*
* Copyright (c) IBM Corporation 2017. All Rights Reserved.
* Project name: java-async-util
* This project is licensed under the Apache License 2.0, see LICENSE.
*/

package com.ibm.asyncutil.iteration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.ibm.asyncutil.iteration.AsyncIterator.End;
import com.ibm.asyncutil.util.Combinators;
import com.ibm.asyncutil.util.Either;

public class BufferedAsyncQueueTest extends AbstractAsyncQueueTest {
  private final static int BUFFER = 5;
  private BoundedAsyncQueue<Integer> queue;

  @Before
  public void makeQueue() {
    this.queue = AsyncQueues.buffered(BUFFER);
  }

  @Override
  boolean send(final Integer c) {
    return this.queue.send(c).toCompletableFuture().join();
  }

  @Override
  AsyncIterator<Integer> consumer() {
    return this.queue;
  }

  @Override
  void closeImpl() {
    this.queue.terminate();
  }


  @Test
  public void bufferedTest() {
    // first five futures should be done immediately
    IntStream.range(0, BUFFER)
        .mapToObj(i -> this.queue.send(i).toCompletableFuture())
        .forEach(f -> {
          Assert.assertTrue(f.isDone());
          Assert.assertTrue(f.join());
        });

    // next 5 should all wait
    final List<CompletableFuture<Boolean>> collect = IntStream.range(0, BUFFER)
        .mapToObj(this.queue::send)
        .map(CompletionStage::toCompletableFuture)
        .map(f -> {
          Assert.assertFalse(f.isDone());
          return f;
        })
        .collect(Collectors.toList());

    for (int i = 0; i < BUFFER; i++) {
      final CompletableFuture<Either<End, Integer>> fut =
          this.queue.nextStage().toCompletableFuture();

      // could change with impl, but with a full queue, futures should already be completed
      Assert.assertTrue(fut.isDone());
      // not closed
      Assert.assertTrue(fut.join().isRight());

      // impl supports fairness (for now), every release, the next waiting future should complete
      for (int j = 0; j < BUFFER; j++) {
        Assert.assertTrue(collect.get(j).isDone() == (j <= i));
      }
    }

    this.queue.terminate();
    for (int i = 0; i < BUFFER * 5; i++) {
      if (i % 2 == 0) {
        this.queue.terminate();
      } else {
        this.queue.send(1);
      }
    }
    this.queue.consume().toCompletableFuture().join();
  }

  @Test
  public void asyncCloseContractTest() {
    // accepted right away
    final List<CompletableFuture<Boolean>> immediate = IntStream
        .range(0, BUFFER)
        .mapToObj(this.queue::send)
        .map(CompletionStage::toCompletableFuture)
        .collect(Collectors.toList());

    Assert.assertTrue(
        Combinators.collect(immediate).toCompletableFuture().join().stream().allMatch(b -> b));

    final List<CompletableFuture<Boolean>> delayeds = IntStream
        .range(0, BUFFER)
        .mapToObj(i -> this.queue.send(i + BUFFER))
        .map(CompletionStage::toCompletableFuture)
        .collect(Collectors.toList());
    Assert.assertFalse(delayeds.stream().map(CompletableFuture::isDone).anyMatch(b -> b));

    // terminate
    final CompletableFuture<Void> closeFuture = this.queue.terminate().toCompletableFuture();

    Assert.assertFalse(delayeds.stream().map(Future::isDone).anyMatch(b -> b));
    Assert.assertFalse(closeFuture.isDone());

    // send after terminate
    final CompletableFuture<Boolean> rejected = this.queue.send(3).toCompletableFuture();

    for (int i = 0; i < BUFFER; i++) {
      // consume one item
      Assert.assertEquals(i,
          this.queue.nextStage().toCompletableFuture().join().right().get().intValue());
      // delayeds less than item should be done
      Assert.assertTrue(delayeds.stream().limit(i + 1).map(Future::isDone).allMatch(b -> b));
      Assert
          .assertTrue(delayeds.stream().limit(i + 1).map(CompletableFuture::join).allMatch(b -> b));
      // delayeds more than item should be pending
      Assert.assertFalse(delayeds.stream().skip(i + 1).map(Future::isDone).anyMatch(b -> b));
      // terminate should not be done until all delayeds are done

      // according to the contract, the terminate future could be done when the last delayed is
      // accepted, however it is not required. only check that if there is outstanding acceptable
      // work, we don't finish terminate
      if (i == BUFFER - 1) {
        Assert.assertFalse(closeFuture.isDone());
      }
    }

    // consume delayed results
    for (int i = BUFFER; i < 2 * BUFFER; i++) {
      Assert.assertEquals(i,
          this.queue.nextStage().toCompletableFuture().join().right().get().intValue());
    }
    Assert.assertFalse(this.queue.nextStage().toCompletableFuture().join().isRight());
    Assert.assertTrue(closeFuture.isDone());
    Assert.assertTrue(rejected.isDone());
    Assert.assertFalse(rejected.join());
  }

  @Override
  Optional<Integer> poll() {
    return this.queue.poll();
  }

}


