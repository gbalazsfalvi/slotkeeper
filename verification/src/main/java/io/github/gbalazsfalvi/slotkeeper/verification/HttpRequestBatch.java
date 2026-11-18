package io.github.gbalazsfalvi.slotkeeper.verification;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Releases a fixed HTTP attempt inventory together while bounding actual in-flight work.
 *
 * <p>The rate-policy exercise uses a smaller connection budget than the last-slot contention
 * exercise. Exhausting the target's connection limit would test transport capacity instead of
 * application admission. Failed attempts remain counted and can never become successful evidence.
 */
final class HttpRequestBatch {
  private HttpRequestBatch() {}

  /**
   * Executes every indexed attempt exactly once, preserving responses and failed-attempt counts.
   *
   * @param count fixed attempt inventory, from one through one thousand
   * @param maxInFlight maximum simultaneous operations, no greater than the attempt count
   * @param operation an owned-target request whose own transport timeout bounds each operation
   * @return immutable observed responses and the number of failed operations
   * @throws InterruptedException when the coordinating thread is interrupted
   */
  static Result execute(int count, int maxInFlight, Operation operation)
      throws InterruptedException {
    if (count < 1 || count > 1000 || maxInFlight < 1 || maxInFlight > count) {
      throw new IllegalArgumentException("Invalid HTTP attempt or in-flight budget.");
    }
    Objects.requireNonNull(operation, "operation");
    var ready = new CountDownLatch(count);
    var start = new CountDownLatch(1);
    var permits = new Semaphore(maxInFlight, true);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ActorSession.Response>> futures = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        final int attempt = index;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  permits.acquire();
                  try {
                    return operation.run(attempt);
                  } finally {
                    permits.release();
                  }
                }));
      }
      try {
        ready.await();
      } finally {
        // Interruption must not strand submitted virtual threads behind the start gate.
        start.countDown();
      }
      var responses = new ArrayList<ActorSession.Response>();
      int failures = 0;
      for (var future : futures) {
        try {
          responses.add(future.get());
        } catch (ExecutionException exception) {
          failures++;
        }
      }
      return new Result(List.copyOf(responses), failures);
    }
  }

  /** Immutable batch accounting; a missing response remains a failed attempt. */
  record Result(List<ActorSession.Response> responses, int transportErrors) {}

  /** One request to an already admitted owned target. */
  @FunctionalInterface
  interface Operation {
    ActorSession.Response run(int index) throws IOException, InterruptedException;
  }
}
