package io.github.gbalazsfalvi.slotkeeper.verification;

import java.time.Instant;

/**
 * Classifies an observed post-limit probe without confusing a documented UTC window reset with
 * bypass.
 */
final class RateWindowVerdict {
  private RateWindowVerdict() {}

  static Outcome classify(int status, String code, Instant lastLimitedAt, Instant probeAt) {
    if (status == 429 && code.equals("RATE_LIMITED")) return Outcome.PASS;
    if (status == 200
        && (lastLimitedAt == null
            || probeAt.getEpochSecond() / 60 > lastLimitedAt.getEpochSecond() / 60))
      return Outcome.INCOMPLETE;
    return Outcome.FAIL;
  }
}
