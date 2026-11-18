package io.github.gbalazsfalvi.slotkeeper.verification;

/** Distinguishes observed control failures, harness errors and missing prerequisites. */
enum Outcome {
  /** Every required observation supports the check. */
  PASS,
  /** An observed product or invariant result contradicts the check. */
  FAIL,
  /** The harness, protocol or dependency could not deliver a trustworthy observation. */
  ERROR,
  /** A required prerequisite or independent evidence was unavailable. */
  INCOMPLETE
}
