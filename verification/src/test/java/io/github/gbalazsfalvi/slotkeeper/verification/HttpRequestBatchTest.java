package io.github.gbalazsfalvi.slotkeeper.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpRequestBatchTest {
  @Test
  void rateExerciseBoundsInFlightWorkWithoutDroppingAttemptsOrFailures() throws Exception {
    var entered = new CountDownLatch(8);
    var release = new CountDownLatch(1);
    var active = new AtomicInteger();
    var peak = new AtomicInteger();
    var visited = ConcurrentHashMap.<Integer>newKeySet();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending =
          executor.submit(
              () ->
                  HttpRequestBatch.execute(
                      130,
                      8,
                      index -> {
                        assertTrue(
                            visited.add(index), "An attempt must be submitted exactly once.");
                        int current = active.incrementAndGet();
                        peak.accumulateAndGet(current, Math::max);
                        entered.countDown();
                        try {
                          assertTrue(release.await(5, TimeUnit.SECONDS));
                          if (index == 5 || index == 77)
                            throw new IOException("Synthetic transport failure.");
                          return new ActorSession.Response(
                              200, Map.of("status", "CANCELLED"), null, 1, Instant.now(), 4190);
                        } finally {
                          active.decrementAndGet();
                        }
                      }));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(8, active.get(), "The first wave must fill, but never exceed, its budget.");
      } finally {
        release.countDown();
      }
      var batch = pending.get(10, TimeUnit.SECONDS);
      assertEquals(130, visited.size());
      assertEquals(128, batch.responses().size());
      assertEquals(2, batch.transportErrors());
      assertEquals(8, peak.get());
      assertEquals(0, active.get());
    }
  }

  @Test
  void invalidBudgetsAreRejectedBeforeAnyRequest() {
    assertThrows(IllegalArgumentException.class, () -> HttpRequestBatch.execute(130, 0, null));
    assertThrows(IllegalArgumentException.class, () -> HttpRequestBatch.execute(0, 8, null));
    assertThrows(IllegalArgumentException.class, () -> HttpRequestBatch.execute(8, 9, null));
  }
}
