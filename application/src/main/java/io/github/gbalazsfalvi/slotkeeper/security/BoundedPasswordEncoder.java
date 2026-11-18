package io.github.gbalazsfalvi.slotkeeper.security;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Shares one per-process work budget for encoding and matching. Encoding matters even without
 * registration: a cold framework provider creates a dummy hash for its timing-attack mitigation.
 */
final class BoundedPasswordEncoder implements PasswordEncoder {
  private final PasswordEncoder delegate;
  private final Semaphore budget;

  BoundedPasswordEncoder(PasswordEncoder delegate, int maximumConcurrentJobs) {
    if (maximumConcurrentJobs < 1)
      throw new IllegalArgumentException("The password work budget must be positive.");
    this.delegate = Objects.requireNonNull(delegate);
    budget = new Semaphore(maximumConcurrentJobs);
  }

  @Override
  public String encode(CharSequence raw) {
    return withinBudget(() -> delegate.encode(raw));
  }

  @Override
  public boolean matches(CharSequence raw, String encoded) {
    return withinBudget(() -> delegate.matches(raw, encoded));
  }

  @Override
  public boolean upgradeEncoding(String encoded) {
    return delegate.upgradeEncoding(encoded);
  }

  private <T> T withinBudget(Supplier<T> work) {
    if (!budget.tryAcquire())
      throw new AuthenticationServiceException("Login work budget exhausted.");
    try {
      return work.get();
    } finally {
      budget.release();
    }
  }
}
