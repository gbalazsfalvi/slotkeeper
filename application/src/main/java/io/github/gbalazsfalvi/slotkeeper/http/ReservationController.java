package io.github.gbalazsfalvi.slotkeeper.http;

import io.github.gbalazsfalvi.slotkeeper.core.*;
import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import io.github.gbalazsfalvi.slotkeeper.security.ActorResolver;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Typed HTTP boundary: actor/owner/role/state are never writable request fields. */
@RestController
@RequestMapping("/api/workspaces/{workspace}")
public final class ReservationController {
  private final ReservationService service;
  private final BookingQueries queries;
  private final ActorResolver actors;

  /**
   * Connects typed HTTP requests to authorized core use cases.
   *
   * @param service core use cases
   * @param queries current scoped read models
   * @param actors trusted identity adapter
   */
  public ReservationController(
      ReservationService service, BookingQueries queries, ActorResolver actors) {
    this.service = service;
    this.queries = queries;
    this.actors = actors;
  }

  /**
   * Writable hold fields; the owner is supplied by the authenticated server context.
   *
   * @param roomId selected scoped room
   * @param startAt exact UTC slot start
   */
  public record HoldRequest(UUID roomId, Instant startAt) {}

  /** A deliberately empty mutation document; unknown properties are rejected. */
  public record EmptyRequest() {}

  /**
   * Creates an owned hold or returns the current authorized retry result.
   *
   * @param auth trusted login
   * @param workspace scope
   * @param input typed payload
   * @param key retry identity
   * @return current hold
   */
  @PostMapping(path = "/holds", consumes = "application/json")
  public ResponseEntity<ReservationView> hold(
      Authentication auth,
      @PathVariable UUID workspace,
      @RequestBody HoldRequest input,
      @RequestHeader("Idempotency-Key") String key) {
    if (input.roomId() == null || input.startAt() == null)
      throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
    ReservationView view =
        service.createHold(actors.require(auth), workspace, input.roomId(), input.startAt(), key);
    return ResponseEntity.status(view.replayed() ? 200 : 201).body(view);
  }

  /**
   * Confirms an owned live hold under the original scoped retry identity.
   *
   * @param auth trusted login
   * @param workspace scope
   * @param id owned reservation
   * @param input empty document
   * @param key retry identity
   * @return committed view
   */
  @PostMapping(path = "/reservations/{id}/confirm", consumes = "application/json")
  public ReservationView confirm(
      Authentication auth,
      @PathVariable UUID workspace,
      @PathVariable UUID id,
      @RequestBody EmptyRequest input,
      @RequestHeader("Idempotency-Key") String key) {
    return service.confirm(actors.require(auth), workspace, id, key);
  }

  /**
   * Cancels an owned reservation while protecting replacement slot assignments.
   *
   * @param auth trusted login
   * @param workspace scope
   * @param id owned reservation
   * @param input empty document
   * @return committed view
   */
  @PostMapping(path = "/reservations/{id}/cancel", consumes = "application/json")
  public ReservationView cancel(
      Authentication auth,
      @PathVariable UUID workspace,
      @PathVariable UUID id,
      @RequestBody EmptyRequest input) {
    return service.cancel(actors.require(auth), workspace, id);
  }

  /**
   * Reads the current effective state of an owned reservation.
   *
   * @param auth trusted login
   * @param workspace scope
   * @param id owned reservation
   * @return current authorized view
   */
  @GetMapping("/reservations/{id}")
  public ReservationView get(
      Authentication auth, @PathVariable UUID workspace, @PathVariable UUID id) {
    return service.get(actors.require(auth), workspace, id);
  }

  /**
   * Lists a bounded page of the current subject's reservations.
   *
   * @param auth trusted login
   * @param workspace scope
   * @param page bounded page
   * @param size bounded limit
   * @return own reservation page
   */
  @GetMapping("/my-bookings")
  public BookingQueries.Page<ReservationView> mine(
      Authentication auth,
      @PathVariable UUID workspace,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return queries.myBookings(actors.require(auth), workspace, page, size);
  }
}
