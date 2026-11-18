package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingPolicyTest {
  private static final Instant NOW = Instant.parse("2026-11-18T09:00:00Z");
  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("Europe/Budapest");
  private final BookingPolicy policy = BookingPolicy.defaults();

  private Reservation held() {
    return Reservation.hold(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        START,
        NOW,
        Duration.ofSeconds(120));
  }

  @Test
  void confirmsBeforeDeadlineAndPreservesOriginalIdentity() {
    Reservation original = held();
    Reservation confirmed = original.confirm(NOW.plusSeconds(30));
    assertEquals(ReservationStatus.CONFIRMED, confirmed.status());
    assertEquals(original.id(), confirmed.id());
    assertEquals(NOW.plusSeconds(30), confirmed.confirmedAt());
    assertEquals(ReservationStatus.HELD, original.status());
  }

  @Test
  void refusesConfirmationExactlyAtDeadline() {
    RuleViolation failure =
        assertThrows(RuleViolation.class, () -> held().confirm(NOW.plusSeconds(120)));
    assertEquals(ProblemCode.HOLD_EXPIRED, failure.code());
  }

  @Test
  void expiryChangesEffectiveStateWithoutMutatingEarlierSnapshot() {
    Reservation original = held();
    assertEquals(ReservationStatus.HELD, original.effectiveStatus(NOW.plusSeconds(119)));
    assertEquals(ReservationStatus.EXPIRED, original.effectiveStatus(NOW.plusSeconds(120)));
    assertEquals(ReservationStatus.HELD, original.status());
  }

  @Test
  void capsHoldAtSlotStart() {
    Reservation reservation =
        Reservation.hold(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            NOW.plusSeconds(30),
            NOW,
            Duration.ofSeconds(120));
    assertEquals(NOW.plusSeconds(30), reservation.expiresAt());
    assertEquals(
        ProblemCode.HOLD_EXPIRED,
        assertThrows(RuleViolation.class, () -> reservation.confirm(NOW.plusSeconds(30))).code());
  }

  @Test
  void cancelledReservationCannotBeRevivedAndCancellationIsHarmless() {
    Reservation cancelled = held().cancel(NOW.plusSeconds(10));
    assertEquals(ReservationStatus.CANCELLED, cancelled.status());
    assertEquals(cancelled, cancelled.cancel(NOW.plusSeconds(20)));
    assertEquals(
        ProblemCode.INVALID_STATE,
        assertThrows(RuleViolation.class, () -> cancelled.confirm(NOW.plusSeconds(30))).code());
  }

  @Test
  void cancellationRetainsConfirmationForNonRefundableAllowance() {
    Reservation cancelled = held().confirm(NOW.plusSeconds(10)).cancel(NOW.plusSeconds(20));
    assertEquals(ReservationStatus.CANCELLED, cancelled.status());
    assertEquals(NOW.plusSeconds(10), cancelled.confirmedAt());
  }

  @Test
  void expiredHoldCannotBeCancelledOrConfirmed() {
    Reservation expired = held().expire(NOW.plusSeconds(120));
    assertEquals(ReservationStatus.EXPIRED, expired.status());
    assertEquals(
        ProblemCode.HOLD_EXPIRED,
        assertThrows(RuleViolation.class, () -> expired.cancel(NOW.plusSeconds(121))).code());
    assertEquals(
        ProblemCode.HOLD_EXPIRED,
        assertThrows(RuleViolation.class, () -> expired.confirm(NOW.plusSeconds(121))).code());
  }

  @Test
  void thirdActiveHoldAndFifthConfirmationAreRefused() {
    assertDoesNotThrow(() -> policy.requireCanHold(1, 3, START, NOW, ZONE, true));
    assertEquals(
        ProblemCode.QUOTA_EXCEEDED,
        assertThrows(RuleViolation.class, () -> policy.requireCanHold(2, 0, START, NOW, ZONE, true))
            .code());
    assertEquals(
        ProblemCode.QUOTA_EXCEEDED,
        assertThrows(RuleViolation.class, () -> policy.requireCanHold(0, 4, START, NOW, ZONE, true))
            .code());
  }

  @Test
  void disabledRoomCannotReceiveNewHolds() {
    assertEquals(
        ProblemCode.INVALID_STATE,
        assertThrows(
                RuleViolation.class, () -> policy.requireCanHold(0, 0, START, NOW, ZONE, false))
            .code());
  }

  @Test
  void validatesFutureBoundaryScheduleAndSlotAlignment() {
    for (Instant invalid :
        new Instant[] {
          NOW,
          NOW.minusSeconds(1),
          START.plusSeconds(1),
          START.plusNanos(1),
          Instant.parse("2026-11-18T07:30:00Z"),
          Instant.parse("2026-11-18T16:00:00Z")
        }) {
      assertEquals(
          ProblemCode.VALIDATION_ERROR,
          assertThrows(
                  RuleViolation.class, () -> policy.requireCanHold(0, 0, invalid, NOW, ZONE, true))
              .code());
    }
  }

  @Test
  void horizonUsesWorkspaceDateRatherThanUtcDate() {
    Instant nearMidnight = Instant.parse("2026-11-18T23:30:00Z");
    assertDoesNotThrow(
        () ->
            policy.requireCanHold(
                0, 0, Instant.parse("2026-11-25T08:00:00Z"), nearMidnight, ZONE, true));
    assertEquals(
        ProblemCode.VALIDATION_ERROR,
        assertThrows(
                RuleViolation.class,
                () ->
                    policy.requireCanHold(
                        0, 0, Instant.parse("2026-11-26T08:00:00Z"), nearMidnight, ZONE, true))
            .code());
  }

  @Test
  void springDstDayUsesRealInstantsForLocalSchedule() {
    assertDoesNotThrow(
        () ->
            policy.requireCanHold(
                0,
                0,
                Instant.parse("2026-03-29T07:00:00Z"),
                Instant.parse("2026-03-29T00:30:00Z"),
                ZONE,
                true));
  }
}
