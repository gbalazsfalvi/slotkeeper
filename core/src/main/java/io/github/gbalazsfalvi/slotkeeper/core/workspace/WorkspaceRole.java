package io.github.gbalazsfalvi.slotkeeper.core.workspace;

/** Permission belongs to one workspace, not to a browser-selected global role. */
public enum WorkspaceRole {
  /** May reserve rooms and manage own reservations. */
  MEMBER,
  /** May also manage rooms and read scoped activity. */
  WORKSPACE_ADMIN
}
