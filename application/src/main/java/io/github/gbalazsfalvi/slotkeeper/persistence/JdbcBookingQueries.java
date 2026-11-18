package io.github.gbalazsfalvi.slotkeeper.persistence;

import io.github.gbalazsfalvi.slotkeeper.core.*;
import io.github.gbalazsfalvi.slotkeeper.core.identity.Actor;
import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded, freshly authorized read models. Other users' reservation identities stay private. */
public final class JdbcBookingQueries implements BookingQueries {
  private final JdbcTemplate sql;
  private final DatabaseTime clock;

  /**
   * Creates the read adapter with runtime-role connections.
   *
   * @param source shared runtime data source
   */
  public JdbcBookingQueries(DataSource source) {
    this(source, new DatabaseTime(source));
  }

  /**
   * Shares independent durable time with other runtime capabilities.
   *
   * @param source primary runtime connections
   * @param clock nondecreasing booking observations
   */
  public JdbcBookingQueries(DataSource source, DatabaseTime clock) {
    this.clock = clock;
    sql = new JdbcTemplate(source);
  }

  @Override
  public List<WorkspaceView> workspaces(Actor actor) {
    return sql.query(
        """
            SELECT w.id,w.name,w.time_zone,m.role
            FROM workspaces w
            JOIN workspace_memberships m ON m.workspace_id=w.id
            JOIN users u ON u.id=m.user_id
            WHERE m.user_id=?
            AND m.active
            AND u.enabled
            ORDER BY w.name
            LIMIT 50
            """,
        (r, n) ->
            new WorkspaceView(
                JdbcRows.uuid(r, "id"),
                r.getString("name"),
                r.getString("time_zone"),
                WorkspaceRole.valueOf(r.getString("role"))),
        actor.userId());
  }

  @Override
  public List<Room> rooms(Actor actor, UUID workspace) {
    member(actor, workspace);
    return sql.query(
        "SELECT * FROM rooms WHERE workspace_id=? ORDER BY name,id LIMIT 20",
        JdbcRows::room,
        workspace);
  }

  @Override
  public Availability availability(Actor actor, UUID workspace, UUID roomId, LocalDate date) {
    Membership member = member(actor, workspace);
    Room room =
        new JdbcBookingTransaction(sql, clock)
            .room(workspace, roomId)
            .orElseThrow(() -> new RuleViolation(ProblemCode.NOT_FOUND));
    Instant now = now();
    LocalDate today = now.atZone(member.timeZone()).toLocalDate();
    if (date == null || date.isBefore(today) || !date.isBefore(today.plusDays(7)))
      throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
    List<SlotView> slots =
        sql.query(
            """
            SELECT s.start_at,s.end_at,r.status,r.expires_at
            FROM slots s
            LEFT JOIN reservations r ON r.workspace_id=s.workspace_id
            AND r.id=s.assigned_reservation_id
            WHERE s.workspace_id=?
            AND s.room_id=?
            AND s.start_at>=?
            AND s.start_at<?
            ORDER BY s.start_at
            LIMIT 16
            """,
            (r, n) -> {
              Instant start = JdbcRows.instant(r, "start_at"),
                  end = JdbcRows.instant(r, "end_at"),
                  expiry = JdbcRows.instant(r, "expires_at");
              String state = r.getString("status");
              boolean held = "HELD".equals(state) && expiry.isAfter(now);
              AvailabilityStatus available =
                  !room.enabled() || !start.isAfter(now)
                      ? AvailabilityStatus.UNAVAILABLE
                      : "CONFIRMED".equals(state)
                          ? AvailabilityStatus.BOOKED
                          : held ? AvailabilityStatus.HELD : AvailabilityStatus.AVAILABLE;
              return new SlotView(start, end, available, held ? expiry : null);
            },
            workspace,
            roomId,
            JdbcRows.timestamp(date.atStartOfDay(member.timeZone()).toInstant()),
            JdbcRows.timestamp(date.plusDays(1).atStartOfDay(member.timeZone()).toInstant()));
    return new Availability(
        date,
        member.timeZone().getId(),
        now,
        new RoomSummary(room.id(), room.name(), room.enabled()),
        slots);
  }

  @Override
  public Page<ReservationView> myBookings(Actor actor, UUID workspace, int page, int size) {
    member(actor, workspace);
    pageBounds(page, size);
    Instant now = now();
    List<ReservationView> items =
        sql
            .query(
                "SELECT * FROM reservations WHERE workspace_id=? AND owner_id=? ORDER BY start_at DESC,id LIMIT ? OFFSET ?",
                JdbcRows::reservation,
                workspace,
                actor.userId(),
                size + 1,
                page * size)
            .stream()
            .map(r -> ReservationView.of(r, now, false))
            .toList();
    return new Page<>(
        items.subList(0, Math.min(size, items.size())), page, size, items.size() > size);
  }

  @Override
  public Page<BookingEvent> activity(Actor actor, UUID workspace, int page, int size) {
    member(actor, workspace).requireAdmin();
    pageBounds(page, size);
    List<BookingEvent> items =
        sql.query(
            "SELECT * FROM booking_events WHERE workspace_id=? ORDER BY occurred_at DESC,id LIMIT ? OFFSET ?",
            JdbcRows::event,
            workspace,
            size + 1,
            page * size);
    return new Page<>(
        items.subList(0, Math.min(size, items.size())), page, size, items.size() > size);
  }

  Membership member(Actor actor, UUID workspace) {
    return new JdbcBookingTransaction(sql, clock)
        .membership(workspace, actor.userId())
        .orElseThrow(() -> new RuleViolation(ProblemCode.NOT_FOUND));
  }

  private Instant now() {
    return clock.now();
  }

  private static void pageBounds(int page, int size) {
    if (page < 0 || page > 1000 || size < 1 || size > 50)
      throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
  }
}
