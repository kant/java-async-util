//
// (C) Copyright IBM Corp. 2005 All Rights Reserved.
//
// Contact Information:
//
// IBM Corporation
// Legal Department
// 222 South Riverside Plaza
// Suite 1700
// Chicago, IL 60606, USA
//
// END-OF-HEADER
//
// -----------------------
// @author: rkhadiwala
//
// Date: Feb 14, 2017
// ---------------------

package com.ibm.async_util.iteration;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * A version of {@link AsyncChannel} that provides a mechanism for backpressure.
 *
 * <p>The documentation from {@link AsyncChannel} largely applies here. Backpressure refers to the
 * signal sent to senders that the channel is "full" and the sender should stop sending values for
 * some period of time. Typically a channel becomes "full" because values are being sent into the
 * channel faster than the consumer is capable of consuming them. Without backpressure, the senders
 * could cause an out of memory condition if they eventually sent too many messages into the
 * channel. Users are expected to respect backpressure by refraining from making a subsequent call
 * to {@link #send} until the previous call completes.
 *
 * <p>Currently you can produce a bounded channel with {@link AsyncChannels#bounded()} or {@link
 * AsyncChannels#buffered(int)}.
 *
 * <p>Consider this example implemented without backpressure
 *
 * <pre>{@code
 * AsyncChannel channel = AsyncChannels.unbounded();
 * pool.submit(() -> {
 *   while(keepGoing) {
 *     channel.send(Completed.success(Optional.of(i++)));
 *   }
 *   channel.terminate();
 * });
 * channel.forEach(i -> {
 *   slowWriteToDisk(i);
 * });
 * }</pre>
 *
 * Because generating work is a cheap in memory operation but consuming it is a slow IO operation,
 * the sender will dramatically outpace the consumer in this case. Soon, the process will run out of
 * memory, as the sender continues to queue ints for the consumer to write. Instead we can use a
 * bounded channel:
 *
 * <pre>{@code
 * AsyncChannel channel = AsyncChannels.bounded();
 *
 * // blocking sender
 * pool.submit(() -> {
 *   while(shouldContinue()) {
 *     ProductionBlocking.get(channel.send(Completed.success(Optional.of(i++))));
 *   }
 *   channel.terminate();
 * });
 *
 * // alternative: async sender
 * AsyncIterators
 *
 *   // AsyncIterator from 1...infinity
 *  .iterate(i -> i + 1)
 *
 *  // send to channel
 *  .map(i -> channel.send(Completed.success(Optional.of(i))))
 *
 *  // consumes futures one by one
 *  .consumeWhile(ig -> shouldContinue())
 *
 *  // finished, terminate channel
 *  .onComplete(ig -> channel.terminate());
 *
 *  // consumer doesn't know or care channel is bounded
 * channel.forEach(i -> {
 *   slowWriteToDisk(i);
 * });
 * }</pre>
 *
 * <p>An important point is that trying to send is the only way to be notified that the queue is
 * full. In practice, this means that if your number of senders is very large you can still consume
 * too much memory even if you are respecting the send interface.
 *
 * @param <T>
 * @see AsyncIterator
 * @see AsyncChannel
 * @see AsyncChannels
 */
public interface BoundedAsyncChannel<T> extends AsyncIterator<T> {

  /**
   * Send a value into this channel that can be consumed via the {@link AsyncIterator} interface.
   *
   * <p>This method is thread safe - multiple threads can send values into this channel
   * concurrently. This channel is bounded, so after a call to {@code send} a future is returned to
   * the sender. When the future completes, consumption has progressed enough that the channel is
   * again willing to accept messages. The implementation may decide when a channel is writable, it
   * could require that all outstanding values are consumed by the consumer, it could allow a
   * certain number of values to be buffered before applying back pressure, or it could use some out
   * of band metric to decide.
   *
   * <p>Note that {@link #close()} is the <b>only</b> way to terminate the channel. Specifically,
   * exceptions don't terminate the channel. See {@link #close()} for details
   *
   * @param item element to send into the channel
   * @return A future that completes when the channel is ready to accept another message. It
   *     completes with true if the item was accepted, false if it was rejected because the channel
   *     has been closed
   * @see AsyncChannel#send
   */
  CompletionStage<Boolean> send(T item);

  /**
   * Close the channel.
   *
   * <p>After the channel is closed, all subsequent sends will be rejected, returning false. After
   * the consumer consumes whatever was sent before the terminate, the consumer will receive
   * Optional.empty(). When the {@link CompletionStage} returned by this method completes, no more
   * messages will ever make it into the channel. Equivalently, all {@code true} futures generated
   * by calls to {@link #send} will have been completed by the time the returned future completes.
   *
   * <p>Note that {@link #close()} is the <b>only</b> way to terminate the channel. Specifically,
   * exceptions don't terminate the channel. This is consistent with the interface on {@link
   * AsyncIterator}; While higher level methods generally stop iteration on exception or empty,
   * {@link AsyncIterator#nextFuture()} can still return exceptions and iteration may continue. This
   * allows users of AsyncChannel/AsyncIterator to continue iterating over possibly exceptional
   * values, at the cost of having to use nextFuture directly to do so.
   *
   * @return A future that indicates when all sends that were sent before the {@link #close()} have
   *     made it into the channel
   * @see AsyncChannel#terminate()
   */
  CompletionStage<Void> close();

  /**
   * Get a result from the channel if there is one ready right now.
   *
   * <p>This method consumes parts of the channel, so like the consumption methods on {@link
   * AsyncIterator}, this method should be used in a single threaded fashion. After {@link #close()}
   * is called and all outstanding results are consumed, poll will always return empty. <br>
   * Notice that the channel being closed is indistinguishable from the channel being transiently
   * empty. To discover that no more results will ever be available, you must use the normal means
   * on {@link AsyncIterator}: Either calling {@link #nextFuture()} and seeing if the result is
   * empty when the future completes, or using one of the consumer methods that only complete once
   * the channel has been closed.
   *
   * @return A value if there was one immediately available in the channel, empty if the channel is
   *     currently empty.
   * @see AsyncChannel#poll()
   */
  Optional<T> poll();
}