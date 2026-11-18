package io.github.gbalazsfalvi.slotkeeper.verification;

import java.util.Objects;

/**
 * An immutable named predicate over actual observations, without untrusted text values.
 *
 * @param kind the independently specified check
 * @param passed whether the observations satisfied it
 */
record StateAssertion(AssertionKind kind, boolean passed) {
  /**
   * Validates the assertion identity.
   *
   * @param kind the check identity
   * @param passed its observed result
   */
  public StateAssertion {
    Objects.requireNonNull(kind);
  }
}
