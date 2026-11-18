package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import io.github.gbalazsfalvi.slotkeeper.core.workspace.Membership;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.Room;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Transaction-local capabilities. Implementations scope every query and preserve lock ordering.
 * Idempotency precedes actor guard, then room/slot, then reservation locks.
 */
public interface BookingTransaction {
  /**
   * Returns a current scoped membership, or an empty result.
   *
   * @param workspaceId requested workspace scope
   * @param actorId authenticated subject
   * @return the current membership if available
   */
  Optional<Membership> membership(UUID workspaceId, UUID actorId);

  /**
   * Returns a room only within the requested workspace.
   *
   * @param workspaceId requested workspace scope
   * @param roomId scoped room identity
   * @return the room if available in the requested scope
   */
  Optional<Room> room(UUID workspaceId, UUID roomId);

  /**
   * Locks or creates a transaction-local retry identity.
   *
   * @param scope authenticated retry scope
   * @param fingerprint validated typed request fingerprint
   * @return the locked retry record
   */
  IdempotencyEntry lockIdempotency(IdempotencyScope scope, String fingerprint);

  /**
   * Serializes an actor's allowance decisions in one workspace.
   *
   * @param workspaceId requested workspace scope
   * @param actorId authenticated subject
   */
  void lockActor(UUID workspaceId, UUID actorId);

  /**
   * Locks the scoped room/slot and reads its assignment.
   *
   * @param workspaceId requested workspace scope
   * @param roomId scoped room identity
   * @param startAt exact slot start
   * @return the locked slot snapshot
   */
  SlotSnapshot lockSlot(UUID workspaceId, UUID roomId, Instant startAt);

  /**
   * Reads a reservation only for its exact workspace and owner.
   *
   * @param workspaceId requested workspace scope
   * @param reservationId reservation identity
   * @param ownerId immutable owner to match
   * @return the owned reservation if available
   */
  Optional<Reservation> ownedReservation(UUID workspaceId, UUID reservationId, UUID ownerId);

  /**
   * Locks a reservation after its slot and verifies its immutable owner/scope.
   *
   * @param workspaceId requested workspace scope
   * @param reservationId reservation identity
   * @param ownerId immutable owner to match
   * @return the locked scoped reservation
   */
  Reservation lockReservation(UUID workspaceId, UUID reservationId, UUID ownerId);

  /**
   * Reads fresh database time after relevant lock waits.
   *
   * @return fresh database time
   */
  Instant now();

  /**
   * Counts unexpired holds for one actor/workspace at the supplied decision time.
   *
   * @param workspaceId requested workspace scope
   * @param actorId authenticated subject
   * @param now authoritative decision time
   * @return the scoped unexpired hold count
   */
  int activeHolds(UUID workspaceId, UUID actorId, Instant now);

  /**
   * Counts successful confirmations for a workspace-local slot date, including cancellations.
   *
   * @param workspaceId requested workspace scope
   * @param actorId authenticated subject
   * @param date workspace-local slot date
   * @param zone workspace calendar zone
   * @return the nonrefundable successful confirmation count
   */
  int confirmations(UUID workspaceId, UUID actorId, LocalDate date, ZoneId zone);

  /**
   * Saves a new or locked reservation without changing immutable ownership.
   *
   * @param reservation new or locked immutable reservation
   */
  void save(Reservation reservation);

  /**
   * Assigns an already locked scoped slot to the new reservation.
   *
   * @param workspaceId requested workspace scope
   * @param slotId already locked scoped slot
   * @param reservationId reservation identity
   */
  void assignSlot(UUID workspaceId, UUID slotId, UUID reservationId);

  /**
   * Releases only the supplied matching assignment, never a replacement reservation.
   *
   * @param workspaceId requested workspace scope
   * @param slotId already locked scoped slot
   * @param expectedReservationId only the assignment this operation may release
   */
  void releaseSlot(UUID workspaceId, UUID slotId, UUID expectedReservationId);

  /**
   * Completes the locked retry record with its committed reservation identity.
   *
   * @param entry previously locked scoped retry entry
   * @param reservationId reservation identity
   */
  void completeIdempotency(IdempotencyEntry entry, UUID reservationId);

  /**
   * Appends the applied business transition atomically.
   *
   * @param event successfully applied business transition
   */
  void append(BookingEvent event);
}
