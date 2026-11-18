package io.github.gbalazsfalvi.slotkeeper.core.reservation;

/** The finite lifecycle of one reservation identity. */
public enum ReservationStatus {
  /** Temporary exclusive ownership until the hold deadline. */
  HELD,
  /** Confirmed ownership, no longer subject to the hold deadline. */
  CONFIRMED,
  /** Voluntarily released; the identity cannot be revived. */
  CANCELLED,
  /** The unconfirmed hold deadline passed; the identity cannot be revived. */
  EXPIRED
}
