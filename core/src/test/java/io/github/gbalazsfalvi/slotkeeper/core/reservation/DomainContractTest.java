package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.Membership;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.Room;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.WorkspaceRole;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainContractTest {
  private static final UUID WS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID ROOM = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-11-18T09:00:00Z");
  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("Europe/Budapest");

  private Reservation snapshot(
      Instant end, ReservationStatus state, Instant created, Instant expires, Instant confirmed) {
    return new Reservation(
        UUID.randomUUID(), WS, ROOM, USER, START, end, state, created, expires, confirmed);
  }

  @Test
  void rejectsIncoherentPersistedTimeAndConfirmationHistory() {
    Instant end = START.plusSeconds(1800), expires = NOW.plusSeconds(120);
    assertAll(
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(START, ReservationStatus.HELD, NOW, expires, null)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(end, ReservationStatus.HELD, START, expires, null)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(end, ReservationStatus.HELD, NOW, NOW, null)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(end, ReservationStatus.HELD, NOW, START.plusSeconds(1), null)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(end, ReservationStatus.CONFIRMED, NOW, expires, null)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    snapshot(end, ReservationStatus.CONFIRMED, NOW, expires, NOW.minusSeconds(1))),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(end, ReservationStatus.CONFIRMED, NOW, expires, expires)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(end, ReservationStatus.HELD, NOW, expires, NOW.plusSeconds(1))),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(end, ReservationStatus.EXPIRED, NOW, expires, NOW.plusSeconds(1))));
  }

  @Test
  void rejectsInvalidTrustedPolicyAndNegativeStoreCounts() {
    assertAll(
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> new BookingPolicy(Duration.ofSeconds(-1), 2, 4, 7)),
        () ->
            assertThrows(
                IllegalArgumentException.class, () -> new BookingPolicy(Duration.ZERO, 2, 4, 7)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> new BookingPolicy(Duration.ofSeconds(120), 0, 4, 7)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> new BookingPolicy(Duration.ofSeconds(120), 2, 0, 7)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> new BookingPolicy(Duration.ofSeconds(120), 2, 4, 0)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> BookingPolicy.defaults().requireCanHold(-1, 0, START, NOW, ZONE, true)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> BookingPolicy.defaults().requireCanHold(0, -1, START, NOW, ZONE, true)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> BookingPolicy.defaults().requireConfirmationAllowance(-1)));
    assertDoesNotThrow(
        () -> BookingPolicy.defaults().requireSlot(START.plusSeconds(1800), NOW, ZONE));
    assertThrows(
        RuleViolation.class,
        () -> BookingPolicy.defaults().requireSlot(START.plusSeconds(900), NOW, ZONE));
  }

  @Test
  void expiryAndClockGuardsPreserveTerminalOrEarlierSnapshots() {
    Reservation held =
        Reservation.hold(UUID.randomUUID(), WS, ROOM, USER, START, NOW, Duration.ofSeconds(120));
    assertSame(held, held.expire(NOW));
    Reservation confirmed = held.confirm(NOW.plusSeconds(1));
    assertSame(confirmed, confirmed.expire(NOW.plusSeconds(500)));
    assertEquals(
        ProblemCode.INVALID_STATE,
        assertThrows(RuleViolation.class, () -> held.confirm(NOW.minusSeconds(1))).code());
    for (Duration ttl : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> Reservation.hold(UUID.randomUUID(), WS, ROOM, USER, START, NOW, ttl));
    }
  }

  @Test
  void aWorkspaceAdminRoleDoesNotExistOnAMemberMembership() {
    assertEquals(
        ProblemCode.FORBIDDEN,
        assertThrows(
                RuleViolation.class,
                () -> new Membership(WS, USER, WorkspaceRole.MEMBER, ZONE).requireAdmin())
            .code());
    assertDoesNotThrow(
        () -> new Membership(WS, USER, WorkspaceRole.WORKSPACE_ADMIN, ZONE).requireAdmin());
  }

  @Test
  void retryKeysAreBoundedAsciiAndFingerprintsHaveAnIndependentGoldenVector() {
    for (String key : new String[] {"short", "a".repeat(129), "contains\nnewline", "nonascii-é"}) {
      assertEquals(
          ProblemCode.VALIDATION_ERROR,
          assertThrows(
                  RuleViolation.class,
                  () -> new IdempotencyScope(WS, USER, IdempotencyScope.Operation.CREATE_HOLD, key))
              .code());
    }
    assertThrows(
        RuleViolation.class,
        () -> new IdempotencyScope(WS, USER, IdempotencyScope.Operation.CREATE_HOLD, null));
    assertDoesNotThrow(
        () ->
            new IdempotencyScope(
                WS, USER, IdempotencyScope.Operation.CREATE_HOLD, "a".repeat(128)));
    assertDoesNotThrow(
        () -> new IdempotencyScope(WS, USER, IdempotencyScope.Operation.CREATE_HOLD, "12345678"));
    assertEquals(
        "23f5f835d8c2a62e48afc7753fb70cd63a28a3d112d0f12efab1ec447d56d01a",
        RequestFingerprint.hold(WS, USER, ROOM, START));
    assertNotEquals(
        RequestFingerprint.hold(WS, USER, ROOM, START), RequestFingerprint.confirm(WS, USER, ROOM));
  }

  @Test
  void slotSnapshotsCannotConnectDifferentScopesRoomsOrInstants() {
    Room room = new Room(ROOM, WS, "Oak", "Quiet", true);
    Reservation[] invalid = {
      Reservation.hold(
          UUID.randomUUID(), UUID.randomUUID(), ROOM, USER, START, NOW, Duration.ofSeconds(120)),
      Reservation.hold(
          UUID.randomUUID(), WS, UUID.randomUUID(), USER, START, NOW, Duration.ofSeconds(120)),
      Reservation.hold(
          UUID.randomUUID(), WS, ROOM, USER, START.plusSeconds(1800), NOW, Duration.ofSeconds(120))
    };
    for (Reservation reservation : invalid)
      assertThrows(
          IllegalArgumentException.class,
          () -> new SlotSnapshot(UUID.randomUUID(), room, START, reservation));
  }
}
