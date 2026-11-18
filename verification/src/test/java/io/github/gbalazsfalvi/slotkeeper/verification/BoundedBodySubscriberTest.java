package io.github.gbalazsfalvi.slotkeeper.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class BoundedBodySubscriberTest {
  @Test
  void abortsAtByteBudgetWithoutRetainingTheExcessBody() {
    var subscriber = new BoundedBodySubscriber(4);
    var subscription = new RecordingSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {4, 5})));
    assertTrue(subscription.cancelled);
    assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
  }

  @Test
  void preservesUtf8ChunksWithinTheBudgetExactly() {
    var subscriber = new BoundedBodySubscriber(32);
    subscriber.onSubscribe(new RecordingSubscription());
    subscriber.onNext(
        List.of(
            ByteBuffer.wrap("{\"".getBytes(StandardCharsets.UTF_8)),
            ByteBuffer.wrap("ok\":true}".getBytes(StandardCharsets.UTF_8))));
    subscriber.onComplete();
    assertEquals(
        "{\"ok\":true}",
        new String(subscriber.getBody().toCompletableFuture().join(), StandardCharsets.UTF_8));
  }

  private static final class RecordingSubscription implements Flow.Subscription {
    boolean cancelled;

    @Override
    public void request(long count) {}

    @Override
    public void cancel() {
      cancelled = true;
    }
  }
}
