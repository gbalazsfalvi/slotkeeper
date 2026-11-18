package io.github.gbalazsfalvi.slotkeeper.core.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Trusted identity resolved by the authentication adapter, never from a booking body.
 *
 * @param userId immutable authenticated subject
 */
public record Actor(UUID userId) {
  /**
   * Requires a concrete authenticated subject.
   *
   * @param userId immutable authenticated subject
   */
  public Actor {
    Objects.requireNonNull(userId);
  }
}
