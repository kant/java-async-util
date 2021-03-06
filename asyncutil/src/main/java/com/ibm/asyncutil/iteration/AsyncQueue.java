/*
* Copyright (c) IBM Corporation 2017. All Rights Reserved.
* Project name: java-async-util
* This project is licensed under the Apache License 2.0, see LICENSE.
*/

package com.ibm.asyncutil.iteration;

import java.util.Optional;

/**
 * An unbounded async multi-producer-single-consumer queue.
 *
 * <p>
 * This class provides a queue abstraction that allows multiple senders to place values into the
 * queue synchronously, and a single consumer to consume values as they become available
 * asynchronously. You can construct an {@link AsyncQueue} with the static methods on
 * {@link AsyncQueues}.
 *
 * <p>
 * This interface represents an <i> unbounded </i> queue, meaning there is no mechanism to notify
 * the sender that the queue is "full" (nor is there a notion of the queue being full to begin
 * with). The queue will continue to accept values as fast as the senders can {@link #send} them,
 * regardless of the rate at which the values are being consumed. If senders produce a lot of values
 * much faster than the consumption rate, it will lead to an out of memory error, so users are
 * responsible for enforcing that the queue does not grow too large. If you would like a queue
 * abstraction that provides backpressure, see {@link BoundedAsyncQueue}.
 *
 * <p>
 * This queue can be terminated by someone calling {@link #terminate()}, which can be called by
 * consumers or senders. It is strongly recommended that all instances of this class eventually be
 * terminated. Most terminal operations on {@link AsyncIterator} return
 * {@link java.util.concurrent.CompletionStage CompletionStages} whose stage will not complete until
 * the queue is terminated. After the queue is terminated, subsequent {@link #send}s are rejected,
 * though consumers of the queue will still receive any values that were sent before the
 * termination.
 *
 * <p>
 * Typically you'll want to use a queue when you have some "source" of items, and want to consume
 * them asynchronously as the become available. Some examples of sources could be a collection of
 * {@link java.util.concurrent.CompletionStage CompletionStages}, bytes off of a socket, results
 * produced by dedicated worker threads, etc. Suppose you had a scenario where you had many threads
 * doing some CPU intensive computation, and you'd send their answers off to some server one at a
 * time.
 *
 * <pre>
 * {@code
 * AsyncQueue<Integer> queue = AsyncQueues.unbounded();
 * for (i = 0; i < numThreads; i++) {
 *   // spawn threads that send results to queue
 *   threadpool.submit(() -> {
 *      while (canStillCompute) {
 *        int num = computeReallyExpensiveThing();
 *        queue.send(num);
 *      }
 *    });
 * }
 *
 * //consumer of queue, sending numbers to a server one at a time
 * queue
 *   // lazily map numbers to send
 *   .thenCompose(number -> sendToServer(number))
 *   // consume all values
 *   .consume()
 *   // iteration stopped (meaning queue was terminated)
 *   .thenAccept(ig -> sendToServer("no more numbers!");
 *
 * threadpool.awaitTermination();
 * // terminate the queue, done computing
 * queue.terminate();
 *
 * }
 * </pre>
 *
 * <p>
 * It is also convenient to use a queue to merge many {@link AsyncIterator}s together. Consider the
 * destination server in the previous example, now with many compute servers sending the numbers
 * they were computing. If we used {@link AsyncIterator#concat} in the following example, we would
 * wait until we got all the work from the first iterator to move onto the next. With a queue we
 * instead process each number as soon as it becomes available.
 *
 * <pre>
 * {@code
 * AsyncIterator<Integer> getNumbersFrom(ServerLocation ip);
 * AsyncQueue queue = AsyncQueues.unbounded();
 * futures = ips.stream()
 *
 *    // get an AsyncIterator of numbers from each server
 *   .map(this::getNumbersFrom)
 *
 *    // send each number on each iterator into the queue as they arrive
 *   .forEach(asyncIterator -> asyncIterator.forEach(t -> queue.send(t)))
 *
 *   // bundle futures into a list
 *   .collect(Collectors.toList());
 *
 * // terminate the queue whenever we're done sending
 * Combinators.allOf(futures).thenAccept(ignore -> queue.terminate());
 *
 * // prints each number returned by servers as they arrive
 * queue
 *   .forEach(num -> System.out.println(num))
 *   .thenAccept(ig -> System.out.println("finished getting all numbers")));
 * }
 * </pre>
 *
 * <p>
 * A reminder, all topics addressed in the documentation of {@link AsyncIterator} apply to this
 * interface as well. Most importantly this means:
 *
 * <ul>
 * <li>Consumption of an AsyncIterator is <b> not </b> thread safe
 * <li>Lazy methods on AsyncIterator like thenApply/thenCompose don't consume anything. Make sure
 * you actually use a consumption operation somewhere, otherwise no one will ever read what was sent
 * </ul>
 *
 * @author Ravi Khadiwala
 * @param <T> The type of the elements in this queue
 * @see AsyncQueues
 * @see BoundedAsyncQueue
 */
public interface AsyncQueue<T> extends AsyncIterator<T> {
  /**
   * Sends a value into this queue that can be consumed via the {@link AsyncIterator} interface.
   *
   * <p>
   * This method is thread safe - multiple threads can send values into this queue concurrently.
   * This queue is unbounded, so it will continue to accept new items immediately and store them in
   * memory until they can be consumed. If you are sending work faster than you can consume it, this
   * can easily lead to an out of memory condition.
   *
   * @param item the item to be sent into the queue
   * @return true if the item was accepted, false if it was rejected because the queue has already
   *         been terminated
   */
  boolean send(T item);

  /**
   * Terminates the queue, disabling {@link #send}.
   *
   * <p>
   * After the queue is terminated all subsequent sends will be rejected, returning false. After the
   * consumer consumes whatever was sent before the terminate, the consumer will receive an end of
   * iteration notification.
   *
   * <p>
   * This method is thread-safe, and can be called multiple times. An attempt to terminate after
   * termination has already occurred is a no-op.
   */
  void terminate();

  /**
   * Gets a result from the queue if one is immediately available.
   *
   * <p>
   * This method consumes parts of the queue, so like the consumption methods on
   * {@link AsyncIterator}, this method is not thread-safe and should be used in a single threaded
   * fashion. After {@link #terminate()} is called and all outstanding results are consumed, poll
   * will always return empty. This method <b> should not </b> be used if there are null values in
   * the queue. <br>
   * Notice that the queue being closed is indistinguishable from the queue being transiently empty.
   * To discover that no more results will ever be available, you must use the normal means on
   * {@link AsyncIterator}: either calling {@link #nextStage()} and seeing if the result indicates
   * an end of iteration when the future completes, or using one of the consumer methods that only
   * complete once the queue has been closed.
   *
   * @throws NullPointerException if the polled result is null
   * @return a present T value if there was one immediately available in the queue, otherwise empty
   *         if the queue is currently empty
   */
  Optional<T> poll();
}
