package io.github.gbalazsfalvi.slotkeeper.http;

import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.Room;
import io.github.gbalazsfalvi.slotkeeper.security.ActorResolver;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Current per-workspace access gates for member and administrator read models. */
@RestController
public final class WorkspaceController {
  private final BookingQueries queries;
  private final ActorResolver actors;

  /**
   * Connects member and administrator read models to the current identity.
   *
   * @param queries scoped product queries
   * @param actors trusted identity adapter
   */
  public WorkspaceController(BookingQueries queries, ActorResolver actors) {
    this.queries = queries;
    this.actors = actors;
  }

  /**
   * Lists only current authorized workspace memberships.
   *
   * @param auth trusted login
   * @return current authorized memberships
   */
  @GetMapping("/api/workspaces")
  public Map<String, List<BookingQueries.WorkspaceView>> workspaces(Authentication auth) {
    return Map.of("items", queries.workspaces(actors.require(auth)));
  }

  /**
   * Lists a finite set of rooms within the requested authorized workspace.
   *
   * @param auth trusted login
   * @param workspace requested scope
   * @return bounded rooms
   */
  @GetMapping("/api/workspaces/{workspace}/rooms")
  public Map<String, List<Room>> rooms(Authentication auth, @PathVariable UUID workspace) {
    return Map.of("items", queries.rooms(actors.require(auth), workspace));
  }

  /**
   * Reads privacy-preserving availability at the database observation time.
   *
   * @param auth trusted login
   * @param workspace scope
   * @param roomId selected room
   * @param date workspace-local date
   * @return finite availability projection
   */
  @GetMapping("/api/workspaces/{workspace}/availability")
  public BookingQueries.Availability availability(
      Authentication auth,
      @PathVariable UUID workspace,
      @RequestParam UUID roomId,
      @RequestParam LocalDate date) {
    return queries.availability(actors.require(auth), workspace, roomId, date);
  }

  /**
   * Lists successful booking transitions for a current scoped administrator.
   *
   * @param auth trusted login
   * @param workspace scope
   * @param page bounded page
   * @param size bounded limit
   * @return successful booking events for a scoped admin
   */
  @GetMapping("/api/workspaces/{workspace}/activity")
  public BookingQueries.Page<BookingEvent> activity(
      Authentication auth,
      @PathVariable UUID workspace,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return queries.activity(actors.require(auth), workspace, page, size);
  }
}
