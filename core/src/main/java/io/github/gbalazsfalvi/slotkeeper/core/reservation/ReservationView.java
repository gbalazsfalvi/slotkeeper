package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe reservation projection with effective state at an explicit observation time.
 *
 * @param id stable reservation identity
 * @param workspaceId owning workspace
 * @param roomId room identity
 * @param ownerId authorized subject
 * @param startAt inclusive start
 * @param endAt exclusive end
 * @param status effective business state
 * @param createdAt original creation time
 * @param expiresAt original hold deadline
 * @param confirmedAt original confirmation time, or null
 * @param serverNow authoritative observation time
 * @param replayed whether the operation returned an already applied effect
 */
public record ReservationView(
    UUID id,
    UUID workspaceId,
    UUID roomId,
    UUID ownerId,
    Instant startAt,
    Instant endAt,
    ReservationStatus status,
    Instant createdAt,
    Instant expiresAt,
    Instant confirmedAt,
    Instant serverNow,
    boolean replayed) {

  /**
   * Creates a projection without changing historical fields.
   *
   * @param reservation current stored snapshot
   * @param now authoritative observation time
   * @param replay whether an existing effect was reused
   * @return the authorized current-state view
   */
  public static ReservationView of(Reservation reservation, Instant now, boolean replay) {
    return new ReservationView(
        reservation.id(),
        reservation.workspaceId(),
        reservation.roomId(),
        reservation.ownerId(),
        reservation.startAt(),
        reservation.endAt(),
        reservation.effectiveStatus(now),
        reservation.createdAt(),
        reservation.expiresAt(),
        reservation.confirmedAt(),
        now,
        replay);
  }
}
