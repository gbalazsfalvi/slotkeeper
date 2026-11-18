package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable reservation history. Slot ownership is committed separately in the same transaction.
 *
 * @param id stable reservation identity
 * @param workspaceId owning workspace
 * @param roomId scoped room identity
 * @param ownerId authenticated owner, never bound from an HTTP body
 * @param startAt inclusive slot start
 * @param endAt exclusive slot end
 * @param status persisted lifecycle state
 * @param createdAt original creation time
 * @param expiresAt original hold deadline, retained after confirmation
 * @param confirmedAt first successful confirmation time, or null if never confirmed
 */
public record Reservation(
    UUID id,
    UUID workspaceId,
    UUID roomId,
    UUID ownerId,
    Instant startAt,
    Instant endAt,
    ReservationStatus status,
    Instant createdAt,
    Instant expiresAt,
    Instant confirmedAt) {

  /**
   * Validates immutable identities and a coherent stored snapshot.
   *
   * @param id stable reservation identity
   * @param workspaceId owning workspace
   * @param roomId scoped room identity
   * @param ownerId authenticated owner, never bound from an HTTP body
   * @param startAt inclusive slot start
   * @param endAt exclusive slot end
   * @param status persisted lifecycle state
   * @param createdAt original creation time
   * @param expiresAt original hold deadline, retained after confirmation
   * @param confirmedAt first successful confirmation time, or null if never confirmed
   */
  public Reservation {
    Objects.requireNonNull(id);
    Objects.requireNonNull(workspaceId);
    Objects.requireNonNull(roomId);
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(startAt);
    Objects.requireNonNull(endAt);
    Objects.requireNonNull(status);
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(expiresAt);
    if (!endAt.equals(startAt.plus(Duration.ofMinutes(30)))
        || !startAt.isAfter(createdAt)
        || !expiresAt.isAfter(createdAt)
        || expiresAt.isAfter(startAt)) {
      throw new IllegalArgumentException("Incoherent reservation time boundaries.");
    }
    if ((status == ReservationStatus.CONFIRMED && confirmedAt == null)
        || (confirmedAt != null
            && (confirmedAt.isBefore(createdAt) || !confirmedAt.isBefore(expiresAt)))
        || ((status == ReservationStatus.HELD || status == ReservationStatus.EXPIRED)
            && confirmedAt != null)) {
      throw new IllegalArgumentException("Incoherent confirmation history.");
    }
  }

  /**
   * Creates a temporary reservation, never allowing a hold to outlive its slot start.
   *
   * @param id new stable identity
   * @param workspaceId owning workspace
   * @param roomId scoped room
   * @param ownerId authenticated owner
   * @param startAt selected future instant
   * @param now authoritative creation time
   * @param ttl positive hold duration
   * @return the unconfirmed immutable snapshot
   */
  public static Reservation hold(
      UUID id,
      UUID workspaceId,
      UUID roomId,
      UUID ownerId,
      Instant startAt,
      Instant now,
      Duration ttl) {
    Objects.requireNonNull(ttl);
    if (ttl.isNegative() || ttl.isZero())
      throw new IllegalArgumentException("Hold TTL must be positive.");
    Instant deadline = now.plus(ttl);
    if (deadline.isAfter(startAt)) deadline = startAt;
    return new Reservation(
        id,
        workspaceId,
        roomId,
        ownerId,
        startAt,
        startAt.plus(Duration.ofMinutes(30)),
        ReservationStatus.HELD,
        now,
        deadline,
        null);
  }

  /**
   * Resolves logical expiration without mutating a previously observed snapshot.
   *
   * @param now authoritative observation time
   * @return the effective lifecycle state
   */
  public ReservationStatus effectiveStatus(Instant now) {
    Objects.requireNonNull(now);
    return status == ReservationStatus.HELD && !now.isBefore(expiresAt)
        ? ReservationStatus.EXPIRED
        : status;
  }

  /**
   * Confirms a live hold; the deadline is exclusive.
   *
   * @param now fresh authoritative time read after transaction locks
   * @return a confirmed snapshot with its original identity
   * @throws RuleViolation if the hold expired or the state cannot be confirmed
   */
  public Reservation confirm(Instant now) {
    requireLive(now);
    if (status != ReservationStatus.HELD) throw new RuleViolation(ProblemCode.INVALID_STATE);
    return copy(ReservationStatus.CONFIRMED, now);
  }

  /**
   * Cancels a live hold/booking; repeating cancellation has no additional effect.
   *
   * @param now fresh authoritative time
   * @return a terminal snapshot retaining any prior confirmation time
   * @throws RuleViolation if the unconfirmed hold expired
   */
  public Reservation cancel(Instant now) {
    if (status == ReservationStatus.CANCELLED) return this;
    requireLive(now);
    return copy(ReservationStatus.CANCELLED, confirmedAt);
  }

  /**
   * Persists logical expiry when cleanup observes an elapsed hold.
   *
   * @param now authoritative observation time
   * @return an expired snapshot, or this unchanged snapshot
   */
  public Reservation expire(Instant now) {
    return status == ReservationStatus.HELD && effectiveStatus(now) == ReservationStatus.EXPIRED
        ? copy(ReservationStatus.EXPIRED, null)
        : this;
  }

  private void requireLive(Instant now) {
    if (effectiveStatus(now) == ReservationStatus.EXPIRED)
      throw new RuleViolation(ProblemCode.HOLD_EXPIRED);
    if (now.isBefore(createdAt)) throw new RuleViolation(ProblemCode.INVALID_STATE);
  }

  private Reservation copy(ReservationStatus next, Instant confirmation) {
    return new Reservation(
        id, workspaceId, roomId, ownerId, startAt, endAt, next, createdAt, expiresAt, confirmation);
  }
}
