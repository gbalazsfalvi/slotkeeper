package io.github.gbalazsfalvi.slotkeeper.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Cold-provider regression: Spring's dummy-hash creation must share the normal hash-work budget.
 */
class PasswordWorkBudgetTest {
  @Test
  void concurrentColdLoginsCannotRunMoreThanFourEncodingOrMatchingJobs() throws Exception {
    AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    CountDownLatch fourJobs = new CountDownLatch(4),
        release = new CountDownLatch(1),
        start = new CountDownLatch(1);
    PasswordEncoder instrumented =
        new PasswordEncoder() {
          private void work() {
            int count = active.incrementAndGet();
            peak.accumulateAndGet(count, Math::max);
            fourJobs.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Test work barrier timed out.");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            } finally {
              active.decrementAndGet();
            }
          }

          @Override
          public String encode(CharSequence raw) {
            work();
            return "synthetic-test-hash";
          }

          @Override
          public boolean matches(CharSequence raw, String encoded) {
            work();
            return false;
          }
        };
    DaoAuthenticationProvider provider =
        new DaoAuthenticationProvider(
            username -> {
              throw new UsernameNotFoundException("Synthetic missing identity.");
            });
    provider.setPasswordEncoder(new BoundedPasswordEncoder(instrumented, 4));
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Future<AuthenticationException>> attempts = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        attempts.add(
            pool.submit(
                () -> {
                  start.await();
                  return assertThrows(
                      AuthenticationException.class,
                      () ->
                          provider.authenticate(
                              UsernamePasswordAuthenticationToken.unauthenticated(
                                  "missing", "synthetic-incorrect-password")));
                }));
      start.countDown();
      try {
        assertTrue(fourJobs.await(5, TimeUnit.SECONDS));
        assertEquals(4, active.get());
        assertEquals(4, peak.get());
      } finally {
        release.countDown();
      }
      int overloaded = 0;
      for (var attempt : attempts)
        if (attempt.get(5, TimeUnit.SECONDS) instanceof AuthenticationServiceException)
          overloaded++;
      assertTrue(overloaded > 0);
      assertEquals(4, peak.get());
      assertEquals(0, active.get());
    }
  }
}
