package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import io.github.gbalazsfalvi.slotkeeper.core.identity.Actor;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.Membership;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Coordinates scoped decisions while the transaction adapter owns atomicity and durable locks. */
public final class ReservationService {
  private final BookingUnitOfWork unit;
  private final BookingPolicy policy;

  /**
   * Creates the use-case boundary with explicit transactional capabilities.
   *
   * @param unit transactional persistence port
   * @param policy declared booking allowances and schedule
   */
  public ReservationService(BookingUnitOfWork unit, BookingPolicy policy) {
    this.unit = Objects.requireNonNull(unit);
    this.policy = Objects.requireNonNull(policy);
  }

  /**
   * Creates one owned hold or returns the current state of an authorized retry.
   *
   * @param actor trusted authenticated subject
   * @param workspaceId requested workspace scope
   * @param roomId selected room
   * @param startAt exact slot start
   * @param idempotencyKey bounded retry identity
   * @return the committed hold or existing authorized resource
   * @throws RuleViolation for scope, input, state or allowance failure
   */
  public ReservationView createHold(
      Actor actor, UUID workspaceId, UUID roomId, Instant startAt, String idempotencyKey) {
    return unit.execute(
        tx -> {
          Membership membership = member(tx, actor, workspaceId);
          tx.room(workspaceId, roomId)
              .filter(room -> room.workspaceId().equals(workspaceId) && room.id().equals(roomId))
              .orElseThrow(() -> new RuleViolation(ProblemCode.NOT_FOUND));
          IdempotencyScope scope =
              new IdempotencyScope(
                  workspaceId,
                  actor.userId(),
                  IdempotencyScope.Operation.CREATE_HOLD,
                  idempotencyKey);
          IdempotencyEntry entry =
              retry(
                  tx, scope, RequestFingerprint.hold(workspaceId, actor.userId(), roomId, startAt));
          if (entry.reservationId() != null) {
            Reservation existing = owned(tx, actor, workspaceId, entry.reservationId());
            return ReservationView.of(existing, tx.now(), true);
          }
          tx.lockActor(workspaceId, actor.userId());
          SlotSnapshot initial = tx.lockSlot(workspaceId, roomId, startAt);
          Reservation assignment =
              initial.assignment() == null
                  ? null
                  : tx.lockReservation(
                      workspaceId, initial.assignment().id(), initial.assignment().ownerId());
          SlotSnapshot slot =
              new SlotSnapshot(initial.id(), initial.room(), initial.startAt(), assignment);
          // Time is deliberately obtained after every relevant row lock, including an old
          // assignment.
          Instant now = tx.now();
          policy.requireCanHold(
              tx.activeHolds(workspaceId, actor.userId(), now),
              tx.confirmations(
                  workspaceId,
                  actor.userId(),
                  startAt.atZone(membership.timeZone()).toLocalDate(),
                  membership.timeZone()),
              startAt,
              now,
              membership.timeZone(),
              slot.room().enabled());
          releaseInactive(tx, slot, now);
          Reservation reservation =
              Reservation.hold(
                  UUID.randomUUID(),
                  workspaceId,
                  roomId,
                  actor.userId(),
                  startAt,
                  now,
                  policy.holdTtl());
          tx.save(reservation);
          tx.assignSlot(workspaceId, slot.id(), reservation.id());
          tx.completeIdempotency(entry, reservation.id());
          event(tx, reservation, BookingEvent.Kind.HOLD_CREATED, now);
          return ReservationView.of(reservation, now, false);
        });
  }

  /**
   * Confirms an owned live hold without creating a second confirmation on retry.
   *
   * @param actor trusted authenticated subject
   * @param workspaceId requested workspace
   * @param reservationId owned reservation identity
   * @param idempotencyKey scoped retry key
   * @return the committed current-state projection
   * @throws RuleViolation for ownership, expiry, state, allowance or retry conflict
   */
  public ReservationView confirm(
      Actor actor, UUID workspaceId, UUID reservationId, String idempotencyKey) {
    return unit.execute(
        tx -> {
          Membership membership = member(tx, actor, workspaceId);
          Reservation preview = owned(tx, actor, workspaceId, reservationId);
          IdempotencyScope scope =
              new IdempotencyScope(
                  workspaceId, actor.userId(), IdempotencyScope.Operation.CONFIRM, idempotencyKey);
          IdempotencyEntry entry =
              retry(
                  tx,
                  scope,
                  RequestFingerprint.confirm(workspaceId, actor.userId(), reservationId));
          if (entry.reservationId() != null) {
            if (!entry.reservationId().equals(reservationId))
              throw new IllegalStateException("Retry resource is inconsistent.");
            return ReservationView.of(owned(tx, actor, workspaceId, reservationId), tx.now(), true);
          }
          tx.lockActor(workspaceId, actor.userId());
          SlotSnapshot slot = tx.lockSlot(workspaceId, preview.roomId(), preview.startAt());
          Reservation current = tx.lockReservation(workspaceId, reservationId, actor.userId());
          Instant now = tx.now();
          Reservation confirmed = current.confirm(now);
          requireAssignment(slot, current);
          policy.requireConfirmationAllowance(
              tx.confirmations(
                  workspaceId,
                  actor.userId(),
                  current.startAt().atZone(membership.timeZone()).toLocalDate(),
                  membership.timeZone()));
          tx.save(confirmed);
          tx.completeIdempotency(entry, confirmed.id());
          event(tx, confirmed, BookingEvent.Kind.CONFIRMED, now);
          return ReservationView.of(confirmed, now, false);
        });
  }

  /**
   * Cancels an owned reservation without clearing any subsequent slot assignment.
   *
   * @param actor trusted authenticated subject
   * @param workspaceId requested workspace
   * @param reservationId owned reservation identity
   * @return the current canceled state; a repeated cancellation is harmless
   * @throws RuleViolation for missing authority or an invalid/expired state
   */
  public ReservationView cancel(Actor actor, UUID workspaceId, UUID reservationId) {
    return unit.execute(
        tx -> {
          member(tx, actor, workspaceId);
          Reservation preview = owned(tx, actor, workspaceId, reservationId);
          tx.lockActor(workspaceId, actor.userId());
          SlotSnapshot slot = tx.lockSlot(workspaceId, preview.roomId(), preview.startAt());
          Reservation current = tx.lockReservation(workspaceId, reservationId, actor.userId());
          Instant now = tx.now();
          Reservation canceled = current.cancel(now);
          if (current.status() == ReservationStatus.CANCELLED)
            return ReservationView.of(current, now, true);
          requireAssignment(slot, current);
          tx.save(canceled);
          tx.releaseSlot(workspaceId, slot.id(), current.id());
          event(tx, canceled, BookingEvent.Kind.CANCELLED, now);
          return ReservationView.of(canceled, now, false);
        });
  }

  /**
   * Reads only an owned reservation in a current authorized workspace.
   *
   * @param actor authenticated subject
   * @param workspaceId requested workspace
   * @param reservationId owned identity
   * @return the effective state at a fresh database observation time
   * @throws RuleViolation if scope or ownership is unavailable
   */
  public ReservationView get(Actor actor, UUID workspaceId, UUID reservationId) {
    return unit.execute(
        tx -> {
          member(tx, actor, workspaceId);
          Reservation reservation = owned(tx, actor, workspaceId, reservationId);
          return ReservationView.of(reservation, tx.now(), false);
        });
  }

  private static Membership member(BookingTransaction tx, Actor actor, UUID workspaceId) {
    Objects.requireNonNull(actor);
    Objects.requireNonNull(workspaceId);
    return tx.membership(workspaceId, actor.userId())
        .filter(
            value ->
                value.workspaceId().equals(workspaceId) && value.userId().equals(actor.userId()))
        .orElseThrow(() -> new RuleViolation(ProblemCode.NOT_FOUND));
  }

  private static Reservation owned(BookingTransaction tx, Actor actor, UUID workspaceId, UUID id) {
    return tx.ownedReservation(workspaceId, id, actor.userId())
        .filter(
            value ->
                value.workspaceId().equals(workspaceId)
                    && value.ownerId().equals(actor.userId())
                    && value.id().equals(id))
        .orElseThrow(() -> new RuleViolation(ProblemCode.NOT_FOUND));
  }

  private static IdempotencyEntry retry(
      BookingTransaction tx, IdempotencyScope scope, String fingerprint) {
    IdempotencyEntry entry = tx.lockIdempotency(scope, fingerprint);
    if (!scope.equals(entry.scope()))
      throw new IllegalStateException("Retry scope is inconsistent.");
    if (!fingerprint.equals(entry.fingerprint()))
      throw new RuleViolation(ProblemCode.IDEMPOTENCY_CONFLICT);
    return entry;
  }

  private static void requireAssignment(SlotSnapshot slot, Reservation reservation) {
    if (slot.assignment() == null || !slot.assignment().id().equals(reservation.id())) {
      throw new RuleViolation(ProblemCode.INVALID_STATE);
    }
  }

  private static void releaseInactive(BookingTransaction tx, SlotSnapshot slot, Instant now) {
    Reservation previous = slot.assignment();
    if (previous == null) return;
    ReservationStatus state = previous.effectiveStatus(now);
    if (state == ReservationStatus.HELD || state == ReservationStatus.CONFIRMED)
      throw new RuleViolation(ProblemCode.SLOT_UNAVAILABLE);
    if (previous.status() == ReservationStatus.HELD) {
      Reservation expired = previous.expire(now);
      tx.save(expired);
      event(tx, expired, BookingEvent.Kind.EXPIRED, now);
    }
    tx.releaseSlot(previous.workspaceId(), slot.id(), previous.id());
  }

  private static void event(
      BookingTransaction tx, Reservation reservation, BookingEvent.Kind kind, Instant now) {
    tx.append(
        new BookingEvent(
            UUID.randomUUID(),
            reservation.workspaceId(),
            kind,
            reservation.id(),
            reservation.ownerId(),
            reservation.roomId(),
            now));
  }
}
