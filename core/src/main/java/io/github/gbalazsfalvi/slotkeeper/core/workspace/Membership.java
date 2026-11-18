package io.github.gbalazsfalvi.slotkeeper.core.workspace;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * Current database-owned permission and calendar context for a subject/workspace pair.
 *
 * @param workspaceId authorized workspace
 * @param userId authenticated subject
 * @param role permission in this workspace only
 * @param timeZone workspace calendar zone
 */
public record Membership(UUID workspaceId, UUID userId, WorkspaceRole role, ZoneId timeZone) {
  /**
   * Requires complete scoped authority.
   *
   * @param workspaceId authorized workspace
   * @param userId authenticated subject
   * @param role permission in this workspace only
   * @param timeZone workspace calendar zone
   */
  public Membership {
    Objects.requireNonNull(workspaceId);
    Objects.requireNonNull(userId);
    Objects.requireNonNull(role);
    Objects.requireNonNull(timeZone);
  }

  /**
   * Requires workspace administration permission.
   *
   * @throws RuleViolation if this membership is a regular member
   */
  public void requireAdmin() {
    if (role != WorkspaceRole.WORKSPACE_ADMIN) throw new RuleViolation(ProblemCode.FORBIDDEN);
  }
}
