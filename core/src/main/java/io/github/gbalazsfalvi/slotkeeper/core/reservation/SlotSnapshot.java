package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import io.github.gbalazsfalvi.slotkeeper.core.workspace.Room;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Locked room-slot state, including its currently assigned reservation if any.
 *
 * @param id stable slot-row identity
 * @param room scoped room snapshot
 * @param startAt exact slot start
 * @param assignment current reservation, or null for an unassigned slot
 */
public record SlotSnapshot(UUID id, Room room, Instant startAt, Reservation assignment) {
  /**
   * Rejects an inconsistent aggregate returned by an adapter.
   *
   * @param id stable slot-row identity
   * @param room scoped room snapshot
   * @param startAt exact slot start
   * @param assignment current reservation, or null for an unassigned slot
   */
  public SlotSnapshot {
    Objects.requireNonNull(id);
    Objects.requireNonNull(room);
    Objects.requireNonNull(startAt);
    if (assignment != null
        && (!assignment.workspaceId().equals(room.workspaceId())
            || !assignment.roomId().equals(room.id())
            || !assignment.startAt().equals(startAt))) {
      throw new IllegalArgumentException("Slot assignment has a different scope or instant.");
    }
  }
}
