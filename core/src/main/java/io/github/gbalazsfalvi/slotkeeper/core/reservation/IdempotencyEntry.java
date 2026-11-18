package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import java.util.Objects;
import java.util.UUID;

/**
 * A locked retry record; a null resource exists only inside its creating transaction.
 *
 * @param id storage identity
 * @param scope authenticated retry scope
 * @param fingerprint typed request SHA-256, not authentication
 * @param reservationId committed resource identity, or null for a fresh transaction-local record
 */
public record IdempotencyEntry(
    UUID id, IdempotencyScope scope, String fingerprint, UUID reservationId) {
  /**
   * Requires a complete key identity and fingerprint.
   *
   * @param id storage identity
   * @param scope authenticated retry scope
   * @param fingerprint typed request SHA-256, not authentication
   * @param reservationId committed resource identity, or null for a fresh transaction-local record
   */
  public IdempotencyEntry {
    Objects.requireNonNull(id);
    Objects.requireNonNull(scope);
    Objects.requireNonNull(fingerprint);
  }
}
