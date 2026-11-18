package io.github.gbalazsfalvi.slotkeeper.verification;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Cancels oversized HTTP bodies while retaining only the bounded bytes needed for one observation.
 */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
  private final int limit;
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> body = new CompletableFuture<>();
  private Flow.Subscription subscription;

  BoundedBodySubscriber(int limit) {
    if (limit < 1) throw new IllegalArgumentException("Positive response budget required");
    this.limit = limit;
  }

  @Override
  public CompletionStage<byte[]> getBody() {
    return body;
  }

  @Override
  public void onSubscribe(Flow.Subscription value) {
    if (subscription != null) {
      value.cancel();
      return;
    }
    subscription = value;
    value.request(1);
  }

  @Override
  public void onNext(List<ByteBuffer> chunks) {
    if (body.isDone()) return;
    for (ByteBuffer chunk : chunks) {
      if ((long) buffer.size() + chunk.remaining() > limit) {
        subscription.cancel();
        body.completeExceptionally(
            new IOException("Response exceeded the observation byte budget"));
        return;
      }
      byte[] bytes = new byte[chunk.remaining()];
      chunk.get(bytes);
      buffer.writeBytes(bytes);
    }
    subscription.request(1);
  }

  @Override
  public void onError(Throwable failure) {
    body.completeExceptionally(failure);
  }

  @Override
  public void onComplete() {
    body.complete(buffer.toByteArray());
  }
}
