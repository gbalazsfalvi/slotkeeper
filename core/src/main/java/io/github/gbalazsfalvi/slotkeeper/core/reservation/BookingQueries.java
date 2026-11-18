package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import io.github.gbalazsfalvi.slotkeeper.core.identity.Actor;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.Room;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.WorkspaceRole;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Scoped product read models; adapters recheck current membership and enforce bounded queries. */
public interface BookingQueries {
  /**
   * Lists only the subject's current memberships.
   *
   * @param actor authenticated subject
   * @return authorized workspace views
   */
  List<WorkspaceView> workspaces(Actor actor);

  /**
   * Lists bounded room definitions in an authorized workspace.
   *
   * @param actor authenticated subject
   * @param workspaceId requested workspace scope
   * @return the scoped room snapshots
   */
  List<Room> rooms(Actor actor, UUID workspaceId);

  /**
   * Projects effective slot state without exposing another owner's identity.
   *
   * @param actor authenticated subject
   * @param workspaceId requested workspace
   * @param roomId scoped room
   * @param date workspace-local selected date
   * @return availability at an authoritative database observation time
   */
  Availability availability(Actor actor, UUID workspaceId, UUID roomId, LocalDate date);

  /**
   * Lists only the actor's own reservations with bounded pagination.
   *
   * @param actor authenticated subject
   * @param workspaceId requested workspace
   * @param page zero-based page, from zero to1000
   * @param size item limit, from one to50
   * @return an immutable page of current authorized views
   */
  Page<ReservationView> myBookings(Actor actor, UUID workspaceId, int page, int size);

  /**
   * Lists applied booking transitions for a current scoped admin.
   *
   * @param actor authenticated subject
   * @param workspaceId requested workspace
   * @param page zero-based bounded page
   * @param size item limit, from one to50
   * @return an immutable page of successful booking events
   */
  Page<BookingEvent> activity(Actor actor, UUID workspaceId, int page, int size);

  /**
   * Workspace identity, calendar and role for the current subject.
   *
   * @param id workspace identity
   * @param name display name
   * @param timeZone named workspace calendar zone
   * @param role role in this workspace, not a global authorization claim
   */
  record WorkspaceView(UUID id, String name, String timeZone, WorkspaceRole role) {}

  /** Effective product-level slot states, independent of any browser countdown. */
  enum AvailabilityStatus {
    /** No current assignment blocks a new hold. */
    AVAILABLE,
    /** A temporary unexpired reservation owns the slot. */
    HELD,
    /** A confirmed reservation owns the slot. */
    BOOKED,
    /** Schedule, room or past-time policy prevents new holds. */
    UNAVAILABLE
  }

  /**
   * Privacy-preserving slot projection.
   *
   * @param startAt inclusive instant
   * @param endAt exclusive instant
   * @param availability effective availability category
   * @param holdExpiresAt current hold deadline, or null when no live hold exists
   */
  record SlotView(
      Instant startAt, Instant endAt, AvailabilityStatus availability, Instant holdExpiresAt) {}

  /**
   * Minimal selected-room context.
   *
   * @param id scoped room identity
   * @param name current display name
   * @param enabled current admission state
   */
  record RoomSummary(UUID id, String name, boolean enabled) {}

  /**
   * Immutable availability observation.
   *
   * @param date selected workspace-local date
   * @param timeZone named zone used for labels and day boundaries
   * @param serverNow authoritative observation time
   * @param room scoped selected-room summary
   * @param slots finite slot projections
   */
  record Availability(
      LocalDate date, String timeZone, Instant serverNow, RoomSummary room, List<SlotView> slots) {
    /**
     * Copies the collection so callers cannot mutate a previously observed result.
     *
     * @param date selected date
     * @param timeZone named zone
     * @param serverNow database observation time
     * @param room room summary
     * @param slots finite slot projections
     */
    public Availability {
      slots = List.copyOf(slots);
    }
  }

  /**
   * Immutable bounded page, without an expensive unrestricted total count.
   *
   * @param <T> immutable item type
   * @param items selected items
   * @param page zero-based page
   * @param size declared item limit
   * @param hasMore whether another item was observed beyond the limit
   */
  record Page<T>(List<T> items, int page, int size, boolean hasMore) {
    /**
     * Defensively copies the selected items.
     *
     * @param items selected immutable values
     * @param page zero-based page
     * @param size declared limit
     * @param hasMore whether another item exists
     */
    public Page {
      items = List.copyOf(items);
    }
  }
}
