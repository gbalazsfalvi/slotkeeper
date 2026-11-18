package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Explicit booking allowances and schedule; database adapters supply fresh scoped counts/time.
 *
 * @param holdTtl positive temporary hold duration
 * @param maxActiveHolds maximum unexpired holds per actor/workspace
 * @param maxConfirmationsPerDay nonrefundable successful daily confirmation allowance
 * @param horizonDays number of selectable local dates including today
 */
public record BookingPolicy(
    Duration holdTtl, int maxActiveHolds, int maxConfirmationsPerDay, int horizonDays) {
  /** Rejects nonsensical trusted configuration. */
  public BookingPolicy {
    Objects.requireNonNull(holdTtl);
    if (holdTtl.isNegative()
        || holdTtl.isZero()
        || maxActiveHolds < 1
        || maxConfirmationsPerDay < 1
        || horizonDays < 1) {
      throw new IllegalArgumentException("Booking policy values must be positive.");
    }
  }

  /**
   * Returns the documented v1 policy.
   *
   * @return 120-second holds, two active holds, four daily confirmations, seven local dates
   */
  public static BookingPolicy defaults() {
    return new BookingPolicy(Duration.ofSeconds(120), 2, 4, 7);
  }

  /**
   * Validates a new hold against a scoped snapshot inside its transaction.
   *
   * @param activeHolds unexpired actor/workspace holds
   * @param confirmations successful confirmations for the actor/workspace/slot-local-date
   * @param start selected slot start
   * @param now fresh database time
   * @param zone workspace zone
   * @param roomEnabled whether the scoped room admits new holds
   * @throws RuleViolation if a schedule, state or allowance rule fails
   */
  public void requireCanHold(
      int activeHolds,
      int confirmations,
      Instant start,
      Instant now,
      ZoneId zone,
      boolean roomEnabled) {
    if (activeHolds < 0 || confirmations < 0)
      throw new IllegalArgumentException("Counts must not be negative.");
    requireSlot(start, now, zone);
    if (!roomEnabled) throw new RuleViolation(ProblemCode.INVALID_STATE);
    if (activeHolds >= maxActiveHolds) throw new RuleViolation(ProblemCode.QUOTA_EXCEEDED);
    requireConfirmationAllowance(confirmations);
  }

  /**
   * Checks the nonrefundable daily allowance before a first confirmation.
   *
   * @param confirmations already successful confirmations, including subsequently canceled bookings
   * @throws RuleViolation if no allowance remains
   */
  public void requireConfirmationAllowance(int confirmations) {
    if (confirmations < 0) throw new IllegalArgumentException("Counts must not be negative.");
    if (confirmations >= maxConfirmationsPerDay)
      throw new RuleViolation(ProblemCode.QUOTA_EXCEEDED);
  }

  /**
   * Checks a future aligned slot in the workspace's local schedule/horizon.
   *
   * @param start exact selected instant
   * @param now authoritative current instant
   * @param zone workspace zone
   * @throws RuleViolation for invalid or unavailable time selection
   */
  public void requireSlot(Instant start, Instant now, ZoneId zone) {
    Objects.requireNonNull(start);
    Objects.requireNonNull(now);
    Objects.requireNonNull(zone);
    ZonedDateTime local = start.atZone(zone);
    LocalDate today = now.atZone(zone).toLocalDate();
    LocalTime time = local.toLocalTime();
    if (!start.isAfter(now)
        || local.toLocalDate().isBefore(today)
        || local.toLocalDate().isAfter(today.plusDays(horizonDays - 1L))
        || time.isBefore(LocalTime.of(9, 0))
        || !time.isBefore(LocalTime.of(17, 0))
        || (time.getMinute() != 0 && time.getMinute() != 30)
        || time.getSecond() != 0
        || time.getNano() != 0) {
      throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
    }
  }
}
