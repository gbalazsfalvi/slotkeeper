package io.github.gbalazsfalvi.slotkeeper.training;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Fixed synthetic missing-object-scope fixture, completely separate from product identity and
 * storage.
 */
final class TrainingFixture {
  private static final String HARBOR = "10000000-0000-0000-0000-000000000001";
  private static final String STUDIO = "10000000-0000-0000-0000-000000000002";
  private static final String FOREIGN_RESERVATION = "30000000-0000-0000-0000-000000000002";
  private final UUID instanceId;

  /**
   * Creates a fixture whose identity is supplied by the owning rehearsal script.
   *
   * @param instanceId the unique script-owned instance identity
   */
  public TrainingFixture(UUID instanceId) {
    this.instanceId = Objects.requireNonNull(instanceId);
  }

  /**
   * Resolves only the fixed teaching surface, deliberately omitting object-workspace scoping.
   *
   * @param method the HTTP method
   * @param path the raw request path
   * @param actor the reduced synthetic actor marker, not a product session
   * @return a fixed synthetic response
   */
  public Reply reply(String method, String path, String actor) {
    if (method.equals("GET") && path.equals("/api/instance"))
      return new Reply(
          200,
          Map.of(
              "project",
              "Slotkeeper Teaching Target",
              "instanceId",
              instanceId.toString(),
              "mode",
              "TRAINING",
              "version",
              "0.1.0"));
    String expected = "/api/workspaces/" + HARBOR + "/reservations/" + FOREIGN_RESERVATION;
    if (!method.equals("GET") || !path.equals(expected))
      return new Reply(404, Map.of("code", "NOT_FOUND"));
    if (!"harbor-member".equals(actor))
      return new Reply(403, Map.of("code", "FIXTURE_ACTOR_REQUIRED"));
    // Deliberate teaching flaw: the supplied Harbor path and actor do not scope this Studio object.
    return new Reply(
        200,
        Map.of(
            "id",
            FOREIGN_RESERVATION,
            "workspaceId",
            STUDIO,
            "ownerId",
            "20000000-0000-0000-0000-000000000002",
            "roomId",
            "40000000-0000-0000-0000-000000000002",
            "status",
            "CONFIRMED",
            "identityModel",
            "REDUCED_SYNTHETIC_ACTOR"));
  }

  /**
   * An immutable response containing fixed synthetic values only.
   *
   * @param status the HTTP status
   * @param body the fixed public payload
   */
  public record Reply(int status, Map<String, Object> body) {
    /**
     * Freezes the fixture response.
     *
     * @param status the HTTP status
     * @param body fixed synthetic fields
     */
    public Reply {
      body = Map.copyOf(body);
    }
  }
}
