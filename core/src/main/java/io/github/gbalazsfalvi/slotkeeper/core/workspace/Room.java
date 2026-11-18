package io.github.gbalazsfalvi.slotkeeper.core.workspace;

import java.util.Objects;
import java.util.UUID;

/**
 * A room snapshot; changing its enabled state does not rewrite reservation history.
 *
 * @param id stable room identity
 * @param workspaceId owner workspace
 * @param name display name, rendered as text
 * @param description bounded display description
 * @param enabled whether new holds are permitted
 */
public record Room(UUID id, UUID workspaceId, String name, String description, boolean enabled) {
  /**
   * Requires a complete room snapshot.
   *
   * @param id stable room identity
   * @param workspaceId owner workspace
   * @param name display name, rendered as text
   * @param description bounded display description
   * @param enabled whether new holds are permitted
   */
  public Room {
    Objects.requireNonNull(id);
    Objects.requireNonNull(workspaceId);
    Objects.requireNonNull(name);
    Objects.requireNonNull(description);
  }
}
