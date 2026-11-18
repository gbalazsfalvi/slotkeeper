package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Versioned typed request fingerprints distinguish retry input; they do not authenticate a caller.
 */
public final class RequestFingerprint {
  private RequestFingerprint() {}

  /**
   * Hashes the normalized workspace, actor, room and exact instant in a fixed order.
   *
   * @param workspaceId authorized workspace
   * @param actorId authenticated subject
   * @param roomId selected scoped room
   * @param startAt normalized slot instant
   * @return lowercase SHA-256 of the documented UTF-8 fields
   */
  public static String hold(UUID workspaceId, UUID actorId, UUID roomId, Instant startAt) {
    return hash(
        "slotkeeper:hold:v1\n"
            + workspaceId
            + "\n"
            + actorId
            + "\n"
            + roomId
            + "\n"
            + startAt
            + "\n");
  }

  /**
   * Hashes a confirmation's immutable resource identity and authenticated scope.
   *
   * @param workspaceId authorized workspace
   * @param actorId authenticated subject
   * @param reservationId owned reservation
   * @return lowercase SHA-256 of the documented UTF-8 fields
   */
  public static String confirm(UUID workspaceId, UUID actorId, UUID reservationId) {
    return hash(
        "slotkeeper:confirm:v1\n" + workspaceId + "\n" + actorId + "\n" + reservationId + "\n");
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("The Java runtime must supply SHA-256.", failure);
    }
  }
}
