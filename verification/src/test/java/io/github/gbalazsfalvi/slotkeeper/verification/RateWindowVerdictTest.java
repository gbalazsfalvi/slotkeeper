package io.github.gbalazsfalvi.slotkeeper.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateWindowVerdictTest {
  @Test
  void fixedWindowRolloverLeavesBypassProbeIncompleteRatherThanReportingFalseFailure() {
    assertEquals(
        Outcome.INCOMPLETE,
        RateWindowVerdict.classify(
            200,
            "",
            Instant.parse("2026-11-18T12:00:59.999Z"),
            Instant.parse("2026-11-18T12:01:00.002Z")));
    assertEquals(
        Outcome.FAIL,
        RateWindowVerdict.classify(
            200, "", Instant.parse("2026-11-18T12:00:30Z"), Instant.parse("2026-11-18T12:00:31Z")));
    assertEquals(
        Outcome.PASS,
        RateWindowVerdict.classify(
            429,
            "RATE_LIMITED",
            Instant.parse("2026-11-18T12:00:30Z"),
            Instant.parse("2026-11-18T12:00:31Z")));
    assertEquals(
        Outcome.FAIL,
        RateWindowVerdict.classify(
            500,
            "INTERNAL_ERROR",
            Instant.parse("2026-11-18T12:00:59Z"),
            Instant.parse("2026-11-18T12:01:00Z")));
  }
}
