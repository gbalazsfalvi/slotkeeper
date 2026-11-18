package io.github.gbalazsfalvi.slotkeeper.http;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.Room;
import io.github.gbalazsfalvi.slotkeeper.persistence.JdbcRoomAdministration;
import io.github.gbalazsfalvi.slotkeeper.security.ActorResolver;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Finite administrator mutations; a caller cannot set IDs, workspace, owner or role. */
@RestController
@RequestMapping("/api/workspaces/{workspace}/rooms")
public final class RoomController {
  private final JdbcRoomAdministration rooms;
  private final ActorResolver actors;

  /**
   * Connects administrator HTTP requests to scoped room operations.
   *
   * @param rooms scoped administration
   * @param actors trusted subject adapter
   */
  public RoomController(JdbcRoomAdministration rooms, ActorResolver actors) {
    this.rooms = rooms;
    this.actors = actors;
  }

  /**
   * Writable fields for a new room.
   *
   * @param name bounded room name
   * @param description bounded plain text
   */
  public record CreateRoom(String name, String description) {}

  /** Presence-aware writable patch; explicit nulls and unknown fields are rejected. */
  public static final class PatchRoom {
    private String name;
    private String description;
    private Boolean enabled;

    /** Creates an initially empty patch; only supplied properties are applied. */
    public PatchRoom() {}

    /**
     * Sets a supplied name; an explicit JSON null is rejected.
     *
     * @param value bounded room name
     */
    @JsonSetter(nulls = Nulls.FAIL)
    public void setName(String value) {
      name = value;
    }

    /**
     * Sets a supplied description; an explicit JSON null is rejected.
     *
     * @param value bounded plain text
     */
    @JsonSetter(nulls = Nulls.FAIL)
    public void setDescription(String value) {
      description = value;
    }

    /**
     * Sets a supplied admission state; an explicit JSON null is rejected.
     *
     * @param value the requested admission state
     */
    @JsonSetter(nulls = Nulls.FAIL)
    public void setEnabled(Boolean value) {
      enabled = value;
    }
  }

  /**
   * Creates a scoped room and returns its committed definition.
   *
   * @param auth trusted login
   * @param workspace authorized scope
   * @param input writable fields
   * @return created room
   */
  @PostMapping(consumes = "application/json")
  public ResponseEntity<Room> create(
      Authentication auth, @PathVariable UUID workspace, @RequestBody CreateRoom input) {
    return ResponseEntity.status(201)
        .body(rooms.create(actors.require(auth), workspace, input.name(), input.description()));
  }

  /**
   * Changes only permitted room fields for a current workspace administrator.
   *
   * @param auth trusted login
   * @param workspace authorized scope
   * @param id scoped room
   * @param input nonempty writable changes
   * @return changed room
   */
  @PatchMapping(path = "/{id}", consumes = "application/json")
  public Room patch(
      Authentication auth,
      @PathVariable UUID workspace,
      @PathVariable UUID id,
      @RequestBody PatchRoom input) {
    return rooms.patch(
        actors.require(auth), workspace, id, input.name, input.description, input.enabled);
  }
}
