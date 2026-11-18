package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import java.time.Instant;
import java.util.UUID;

/**
 * An applied business transition, written in the same transaction as its effect.
 *
 * @param id event identity
 * @param workspaceId owning workspace
 * @param kind stable transition description
 * @param reservationId reservation identity
 * @param actorId subject owning/applying the transition
 * @param roomId scoped room
 * @param occurredAt authoritative transition time
 */
public record BookingEvent(
    UUID id,
    UUID workspaceId,
    Kind kind,
    UUID reservationId,
    UUID actorId,
    UUID roomId,
    Instant occurredAt) {
  /** Successful reservation transitions. */
  public enum Kind {
    /** A temporary reservation acquired the slot. */
    HOLD_CREATED,
    /** An unexpired hold was confirmed. */
    CONFIRMED,
    /** An owner released the reservation. */
    CANCELLED,
    /** An unconfirmed hold expired. */
    EXPIRED
  }
}
