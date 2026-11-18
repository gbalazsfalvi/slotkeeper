package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import io.github.gbalazsfalvi.slotkeeper.core.identity.Actor;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.Membership;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.Room;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.WorkspaceRole;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ReservationServiceTest {
  private static final UUID WS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID ROOM = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID SLOT = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final Instant NOW = Instant.parse("2026-11-18T09:00:00Z");
  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("Europe/Budapest");
  private static final String KEY = "test-key-123";
  private final Actor actor = new Actor(USER);
  private BookingTransaction tx;
  private ReservationService service;
  private Room room;

  @BeforeEach
  void setUp() {
    tx = mock(BookingTransaction.class);
    BookingUnitOfWork unit =
        new BookingUnitOfWork() {
          @Override
          public <T> T execute(Function<BookingTransaction, T> work) {
            return work.apply(tx);
          }
        };
    service = new ReservationService(unit, BookingPolicy.defaults());
    room = new Room(ROOM, WS, "Oak", "Quiet room", true);
    when(tx.membership(WS, USER))
        .thenReturn(Optional.of(new Membership(WS, USER, WorkspaceRole.MEMBER, ZONE)));
    when(tx.room(WS, ROOM)).thenReturn(Optional.of(room));
    when(tx.now()).thenReturn(NOW);
    when(tx.lockSlot(WS, ROOM, START)).thenReturn(new SlotSnapshot(SLOT, room, START, null));
    when(tx.lockIdempotency(any(), anyString()))
        .thenAnswer(
            invocation ->
                new IdempotencyEntry(
                    UUID.randomUUID(), invocation.getArgument(0), invocation.getArgument(1), null));
  }

  private Reservation held(UUID owner, Instant created) {
    return Reservation.hold(
        UUID.randomUUID(), WS, ROOM, owner, START, created, Duration.ofSeconds(120));
  }

  private void owned(Reservation reservation, Reservation assignment) {
    when(tx.ownedReservation(WS, reservation.id(), USER)).thenReturn(Optional.of(reservation));
    when(tx.lockReservation(WS, reservation.id(), USER)).thenReturn(reservation);
    when(tx.lockSlot(WS, ROOM, START)).thenReturn(new SlotSnapshot(SLOT, room, START, assignment));
  }

  @Test
  void createsOneOwnedEffectUsingTimeReadAfterLocks() {
    ReservationView result = service.createHold(actor, WS, ROOM, START, KEY);
    assertEquals(USER, result.ownerId());
    assertEquals(WS, result.workspaceId());
    assertEquals(ReservationStatus.HELD, result.status());
    assertFalse(result.replayed());
    ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
    verify(tx).save(saved.capture());
    assertEquals(result.id(), saved.getValue().id());
    verify(tx).assignSlot(WS, SLOT, result.id());
    verify(tx).completeIdempotency(any(), eq(result.id()));
    InOrder order = inOrder(tx);
    order.verify(tx).lockActor(WS, USER);
    order.verify(tx).lockSlot(WS, ROOM, START);
    order.verify(tx).now();
  }

  @Test
  void revokedMembershipCannotReuseAnExistingReceipt() {
    when(tx.membership(WS, USER)).thenReturn(Optional.empty());
    assertEquals(
        ProblemCode.NOT_FOUND,
        assertThrows(RuleViolation.class, () -> service.createHold(actor, WS, ROOM, START, KEY))
            .code());
    verify(tx, never()).lockIdempotency(any(), anyString());
    verify(tx, never()).save(any());
  }

  @Test
  void foreignRoomIsUnavailableBeforeReceiptLookup() {
    when(tx.room(WS, ROOM)).thenReturn(Optional.empty());
    assertEquals(
        ProblemCode.NOT_FOUND,
        assertThrows(RuleViolation.class, () -> service.createHold(actor, WS, ROOM, START, KEY))
            .code());
    verify(tx, never()).lockIdempotency(any(), anyString());
  }

  @Test
  void authorizedReplayDoesNotCreateAnotherEffectOrConsumeAnotherAllowance() {
    Reservation original = held(USER, NOW.minusSeconds(1));
    IdempotencyScope scope =
        new IdempotencyScope(WS, USER, IdempotencyScope.Operation.CREATE_HOLD, KEY);
    doReturn(
            new IdempotencyEntry(
                UUID.randomUUID(),
                scope,
                RequestFingerprint.hold(WS, USER, ROOM, START),
                original.id()))
        .when(tx)
        .lockIdempotency(any(), anyString());
    when(tx.ownedReservation(WS, original.id(), USER)).thenReturn(Optional.of(original));
    when(tx.activeHolds(WS, USER, NOW)).thenReturn(2);
    ReservationView result = service.createHold(actor, WS, ROOM, START, KEY);
    assertEquals(original.id(), result.id());
    assertTrue(result.replayed());
    verify(tx, never()).lockActor(any(), any());
    verify(tx, never()).save(any());
    verify(tx, never()).append(any());
  }

  @Test
  void sameKeyDifferentInputCannotReachMutationLocks() {
    IdempotencyScope scope =
        new IdempotencyScope(WS, USER, IdempotencyScope.Operation.CREATE_HOLD, KEY);
    doReturn(new IdempotencyEntry(UUID.randomUUID(), scope, "0".repeat(64), UUID.randomUUID()))
        .when(tx)
        .lockIdempotency(any(), anyString());
    assertEquals(
        ProblemCode.IDEMPOTENCY_CONFLICT,
        assertThrows(RuleViolation.class, () -> service.createHold(actor, WS, ROOM, START, KEY))
            .code());
    verify(tx, never()).lockActor(any(), any());
    verify(tx, never()).save(any());
  }

  @Test
  void anActiveAssignmentPreventsAnotherHold() {
    Reservation other = held(OTHER, NOW.minusSeconds(1));
    when(tx.lockSlot(WS, ROOM, START)).thenReturn(new SlotSnapshot(SLOT, room, START, other));
    when(tx.lockReservation(WS, other.id(), OTHER)).thenReturn(other);
    assertEquals(
        ProblemCode.SLOT_UNAVAILABLE,
        assertThrows(RuleViolation.class, () -> service.createHold(actor, WS, ROOM, START, KEY))
            .code());
    verify(tx, never()).save(any());
    verify(tx, never()).assignSlot(any(), any(), any());
  }

  @Test
  void expiredAssignmentIsReleasedByExactIdentityBeforeReplacement() {
    Reservation expired = held(OTHER, NOW.minusSeconds(200));
    when(tx.lockSlot(WS, ROOM, START)).thenReturn(new SlotSnapshot(SLOT, room, START, expired));
    when(tx.lockReservation(WS, expired.id(), OTHER)).thenReturn(expired);
    ReservationView replacement = service.createHold(actor, WS, ROOM, START, KEY);
    verify(tx).releaseSlot(WS, SLOT, expired.id());
    verify(tx).assignSlot(WS, SLOT, replacement.id());
    ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
    verify(tx, times(2)).save(saved.capture());
    assertEquals(ReservationStatus.EXPIRED, saved.getAllValues().getFirst().status());
    assertEquals(replacement.id(), saved.getAllValues().getLast().id());
  }

  @Test
  void activeHoldAllowanceIsEnforcedBeforeCreatingTheReservation() {
    when(tx.activeHolds(WS, USER, NOW)).thenReturn(2);
    assertEquals(
        ProblemCode.QUOTA_EXCEEDED,
        assertThrows(RuleViolation.class, () -> service.createHold(actor, WS, ROOM, START, KEY))
            .code());
    verify(tx, never()).save(any());
  }

  @Test
  void confirmsTheLockedOwnedReservation() {
    Reservation original = held(USER, NOW.minusSeconds(1));
    owned(original, original);
    ReservationView result = service.confirm(actor, WS, original.id(), KEY);
    assertEquals(ReservationStatus.CONFIRMED, result.status());
    assertEquals(NOW, result.confirmedAt());
    verify(tx)
        .save(
            argThat(
                r -> r.id().equals(original.id()) && r.status() == ReservationStatus.CONFIRMED));
    verify(tx).append(argThat(event -> event.kind() == BookingEvent.Kind.CONFIRMED));
  }

  @Test
  void aWaitThatCrossesExpiryCannotUseAnEarlierClockReading() {
    Reservation original = held(USER, NOW.minusSeconds(110));
    owned(original, original);
    when(tx.now()).thenReturn(NOW.plusSeconds(11));
    assertEquals(
        ProblemCode.HOLD_EXPIRED,
        assertThrows(RuleViolation.class, () -> service.confirm(actor, WS, original.id(), KEY))
            .code());
    InOrder order = inOrder(tx);
    order.verify(tx).lockSlot(WS, ROOM, START);
    order.verify(tx).lockReservation(WS, original.id(), USER);
    order.verify(tx).now();
    verify(tx, never()).save(any());
  }

  @Test
  void confirmedAllowanceIncludesCanceledConfirmationHistory() {
    Reservation original = held(USER, NOW.minusSeconds(1));
    owned(original, original);
    when(tx.confirmations(eq(WS), eq(USER), any(), eq(ZONE))).thenReturn(4);
    assertEquals(
        ProblemCode.QUOTA_EXCEEDED,
        assertThrows(RuleViolation.class, () -> service.confirm(actor, WS, original.id(), KEY))
            .code());
    verify(tx, never()).save(any());
  }

  @Test
  void staleExpiredCancellationCannotReleaseAReplacement() {
    Reservation old = held(USER, NOW.minusSeconds(200));
    Reservation replacement = held(OTHER, NOW.minusSeconds(1));
    owned(old, replacement);
    assertEquals(
        ProblemCode.HOLD_EXPIRED,
        assertThrows(RuleViolation.class, () -> service.cancel(actor, WS, old.id())).code());
    verify(tx, never()).releaseSlot(any(), any(), any());
    verify(tx, never()).save(any());
  }

  @Test
  void repeatedCancellationDoesNotTouchTheNewSlotOwner() {
    Reservation old = held(USER, NOW.minusSeconds(10)).cancel(NOW.minusSeconds(5));
    Reservation replacement = held(OTHER, NOW.minusSeconds(1));
    owned(old, replacement);
    ReservationView result = service.cancel(actor, WS, old.id());
    assertEquals(ReservationStatus.CANCELLED, result.status());
    verify(tx, never()).releaseSlot(any(), any(), any());
    verify(tx, never()).save(any());
  }

  @Test
  void anotherOwnerCannotReachReservationMutationLocks() {
    UUID unavailable = UUID.randomUUID();
    when(tx.ownedReservation(WS, unavailable, USER)).thenReturn(Optional.empty());
    assertEquals(
        ProblemCode.NOT_FOUND,
        assertThrows(RuleViolation.class, () -> service.cancel(actor, WS, unavailable)).code());
    verify(tx, never()).lockActor(any(), any());
    verify(tx, never()).lockSlot(any(), any(), any());
  }

  @Test
  void readsEffectiveExpiredStateOnlyForCurrentOwnerAndWorkspace() {
    Reservation old = held(USER, NOW.minusSeconds(200));
    owned(old, old);
    ReservationView result = service.get(actor, WS, old.id());
    assertEquals(ReservationStatus.EXPIRED, result.status());
    assertEquals(NOW, result.serverNow());
    verify(tx, never()).save(any());
  }

  @Test
  void cancellationReleasesOnlyItsExactAssignmentAndRetainsHistory() {
    Reservation original = held(USER, NOW.minusSeconds(10)).confirm(NOW.minusSeconds(5));
    owned(original, original);
    ReservationView result = service.cancel(actor, WS, original.id());
    assertEquals(ReservationStatus.CANCELLED, result.status());
    assertEquals(original.confirmedAt(), result.confirmedAt());
    verify(tx).releaseSlot(WS, SLOT, original.id());
    verify(tx).append(argThat(e -> e.kind() == BookingEvent.Kind.CANCELLED));
  }

  @Test
  void confirmationReplayReportsCurrentCancellationWithoutAnotherEvent() {
    Reservation original =
        held(USER, NOW.minusSeconds(10)).confirm(NOW.minusSeconds(5)).cancel(NOW.minusSeconds(1));
    owned(original, original);
    IdempotencyScope scope =
        new IdempotencyScope(WS, USER, IdempotencyScope.Operation.CONFIRM, KEY);
    doReturn(
            new IdempotencyEntry(
                UUID.randomUUID(),
                scope,
                RequestFingerprint.confirm(WS, USER, original.id()),
                original.id()))
        .when(tx)
        .lockIdempotency(any(), anyString());
    ReservationView result = service.confirm(actor, WS, original.id(), KEY);
    assertTrue(result.replayed());
    assertEquals(ReservationStatus.CANCELLED, result.status());
    verify(tx, never()).save(any());
    verify(tx, never()).append(any());
  }

  @Test
  void cannotConfirmOrCancelWithoutTheMatchingSlotAssignment() {
    Reservation original = held(USER, NOW.minusSeconds(1));
    owned(original, null);
    assertEquals(
        ProblemCode.INVALID_STATE,
        assertThrows(RuleViolation.class, () -> service.confirm(actor, WS, original.id(), KEY))
            .code());
    Reservation replacement = held(OTHER, NOW.minusSeconds(1));
    owned(original, replacement);
    assertEquals(
        ProblemCode.INVALID_STATE,
        assertThrows(RuleViolation.class, () -> service.cancel(actor, WS, original.id())).code());
    verify(tx, never()).releaseSlot(any(), any(), any());
  }

  @Test
  void aLockedDisabledRoomRejectsNewHoldsDespiteAnEarlierEnabledRead() {
    Room disabled = new Room(ROOM, WS, "Oak", "Quiet", false);
    when(tx.lockSlot(WS, ROOM, START)).thenReturn(new SlotSnapshot(SLOT, disabled, START, null));
    assertEquals(
        ProblemCode.INVALID_STATE,
        assertThrows(RuleViolation.class, () -> service.createHold(actor, WS, ROOM, START, KEY))
            .code());
    verify(tx, never()).save(any());
  }

  @Test
  void alreadyPersistedExpiryDoesNotCreateAnotherExpiryEvent() {
    Reservation expired = held(OTHER, NOW.minusSeconds(200)).expire(NOW);
    when(tx.lockSlot(WS, ROOM, START)).thenReturn(new SlotSnapshot(SLOT, room, START, expired));
    when(tx.lockReservation(WS, expired.id(), OTHER)).thenReturn(expired);
    service.createHold(actor, WS, ROOM, START, KEY);
    verify(tx, times(1)).save(any());
    verify(tx, times(1)).append(any());
    verify(tx).releaseSlot(WS, SLOT, expired.id());
  }

  @Test
  void incorrectlyScopedAdapterResultsFailClosed() {
    when(tx.membership(WS, USER))
        .thenReturn(
            Optional.of(new Membership(UUID.randomUUID(), USER, WorkspaceRole.MEMBER, ZONE)));
    assertEquals(
        ProblemCode.NOT_FOUND,
        assertThrows(RuleViolation.class, () -> service.createHold(actor, WS, ROOM, START, KEY))
            .code());
    when(tx.membership(WS, USER))
        .thenReturn(Optional.of(new Membership(WS, OTHER, WorkspaceRole.MEMBER, ZONE)));
    assertEquals(
        ProblemCode.NOT_FOUND,
        assertThrows(RuleViolation.class, () -> service.createHold(actor, WS, ROOM, START, KEY))
            .code());
    verify(tx, never()).save(any());
  }

  @Test
  void aRetryRecordFromADifferentScopeCannotBeUsed() {
    IdempotencyScope wrong =
        new IdempotencyScope(WS, OTHER, IdempotencyScope.Operation.CREATE_HOLD, KEY);
    doReturn(
            new IdempotencyEntry(
                UUID.randomUUID(), wrong, RequestFingerprint.hold(WS, USER, ROOM, START), null))
        .when(tx)
        .lockIdempotency(any(), anyString());
    assertThrows(
        IllegalStateException.class, () -> service.createHold(actor, WS, ROOM, START, KEY));
    verify(tx, never()).save(any());
  }
}
