package io.github.gbalazsfalvi.slotkeeper.verification;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Allowlisted immutable HTTP metadata; headers, cookies and raw bodies cannot be represented.
 *
 * @param method the HTTP method
 * @param endpoint the fixed path template
 * @param status the observed HTTP status
 * @param code the recognized problem code, or null
 * @param requestId the syntactically valid request identifier, or null
 * @param resourceId the syntactically valid reservation identifier, or null
 * @param workspaceId the synthetic object workspace identity, or null
 * @param ownerId the synthetic object owner identity, or null
 * @param state the recognized reservation state, or null
 * @param latencyMillis the elapsed request time
 * @param observedAt the response observation instant
 */
record SanitizedObservation(
    String method,
    Endpoint endpoint,
    int status,
    String code,
    UUID requestId,
    UUID resourceId,
    UUID workspaceId,
    UUID ownerId,
    String state,
    long latencyMillis,
    Instant observedAt,
    int targetPort,
    UUID roomId,
    Instant startAt,
    Instant endAt,
    Instant createdAt,
    Instant expiresAt,
    Instant confirmedAt,
    Instant serverNow,
    Boolean replayed) {
  private static final Set<String> CODES =
      Set.of(
          "VALIDATION_ERROR",
          "AUTHENTICATION_REQUIRED",
          "INVALID_CREDENTIALS",
          "FORBIDDEN",
          "CSRF_INVALID",
          "NOT_FOUND",
          "SLOT_UNAVAILABLE",
          "QUOTA_EXCEEDED",
          "HOLD_EXPIRED",
          "INVALID_STATE",
          "IDEMPOTENCY_CONFLICT",
          "PAYLOAD_TOO_LARGE",
          "UNSUPPORTED_MEDIA_TYPE",
          "RATE_LIMITED",
          "RESOURCE_BUSY",
          "INTERNAL_ERROR",
          "SERVICE_UNAVAILABLE");
  private static final Set<String> STATES = Set.of("HELD", "CONFIRMED", "CANCELLED", "EXPIRED");

  /**
   * Validates metadata before it becomes exportable.
   *
   * @param method the allowlisted method
   * @param endpoint the fixed endpoint
   * @param status the HTTP status
   * @param code the allowlisted error code
   * @param requestId the request identity
   * @param resourceId the resource identity
   * @param workspaceId the synthetic workspace identity
   * @param ownerId the synthetic owner identity
   * @param state the allowlisted state
   * @param latencyMillis the request latency
   * @param observedAt the observation timestamp
   */
  public SanitizedObservation {
    if (!Set.of("GET", "POST", "PATCH").contains(method)
        || status < 100
        || status > 599
        || latencyMillis < 0
        || !Set.of(0, 4190, 4192, 4193).contains(targetPort)
        || (code != null && !CODES.contains(code))
        || (state != null && !STATES.contains(state)))
      throw new IllegalArgumentException("Metadata is outside the evidence allowlist");
    Objects.requireNonNull(endpoint);
    Objects.requireNonNull(observedAt);
  }

  /**
   * Extracts only typed public fields and discards every other response property.
   *
   * @param method the HTTP method
   * @param endpoint the fixed path template
   * @param status the HTTP status
   * @param body the private parsed body, which is not retained
   * @param latencyMillis elapsed request time
   * @return immutable safe metadata
   */
  public static SanitizedObservation fromResponse(
      String method, Endpoint endpoint, int status, Map<String, Object> body, long latencyMillis) {
    return fromResponse(method, endpoint, status, body, latencyMillis, Instant.now(), 0);
  }

  static SanitizedObservation fromResponse(
      String method,
      Endpoint endpoint,
      int status,
      Map<String, Object> body,
      long latencyMillis,
      Instant observedAt,
      int targetPort) {
    return new SanitizedObservation(
        method,
        endpoint,
        status,
        optionalString(body.get("code")),
        uuid(body.get("requestId")),
        uuid(body.get("id")),
        uuid(body.get("workspaceId")),
        uuid(body.get("ownerId")),
        optionalString(body.get("status") instanceof String ? body.get("status") : null),
        latencyMillis,
        observedAt,
        targetPort,
        uuid(body.get("roomId")),
        instant(body.get("startAt")),
        instant(body.get("endAt")),
        instant(body.get("createdAt")),
        instant(body.get("expiresAt")),
        instant(body.get("confirmedAt")),
        instant(body.get("serverNow")),
        body.get("replayed") instanceof Boolean value ? value : null);
  }

  private static String optionalString(Object value) {
    return value == null ? null : value.toString();
  }

  private static UUID uuid(Object value) {
    return value == null ? null : UUID.fromString(value.toString());
  }

  private static Instant instant(Object value) {
    return value == null ? null : Instant.parse(value.toString());
  }

  /** Safe fixed endpoint names; concrete paths and query strings are not exported. */
  public enum Endpoint {
    INSTANCE,
    CSRF,
    SESSION,
    LOGOUT,
    WORKSPACES,
    ROOMS,
    AVAILABILITY,
    HOLDS,
    RESERVATION,
    CONFIRM,
    CANCEL,
    MY_BOOKINGS,
    ACTIVITY,
    TRAINING_RESERVATION
  }
}
