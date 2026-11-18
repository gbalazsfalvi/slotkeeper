package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A retry identity is private to a subject, workspace and operation.
 *
 * @param workspaceId current authorized workspace
 * @param actorId authenticated subject
 * @param operation business mutation
 * @param key bounded caller retry key
 */
public record IdempotencyScope(UUID workspaceId, UUID actorId, Operation operation, String key) {
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}");

  /**
   * Requires a scoped, bounded ASCII retry identity.
   *
   * @param workspaceId current authorized workspace
   * @param actorId authenticated subject
   * @param operation business mutation
   * @param key bounded caller retry key
   */
  public IdempotencyScope {
    Objects.requireNonNull(workspaceId);
    Objects.requireNonNull(actorId);
    Objects.requireNonNull(operation);
    if (key == null || !KEY.matcher(key).matches())
      throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
  }

  /** The operations whose results are explicitly retained. */
  public enum Operation {
    /** Creates one temporary room reservation. */
    CREATE_HOLD,
    /** Confirms one existing temporary reservation. */
    CONFIRM
  }
}
