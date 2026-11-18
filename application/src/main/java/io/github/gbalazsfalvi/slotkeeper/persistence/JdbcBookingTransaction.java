package io.github.gbalazsfalvi.slotkeeper.persistence;

import io.github.gbalazsfalvi.slotkeeper.core.*;
import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Prepared, scoped PostgreSQL capabilities, valid only inside their enclosing transaction. */
final class JdbcBookingTransaction implements BookingTransaction {
  private final JdbcTemplate sql;
  private final DatabaseTime clock;

  JdbcBookingTransaction(JdbcTemplate sql, DatabaseTime clock) {
    this.sql = sql;
    this.clock = clock;
  }

  @Override
  public Optional<Membership> membership(UUID workspace, UUID actor) {
    return sql
        .query(
            """
            SELECT m.role,w.time_zone
            FROM workspace_memberships m
            JOIN workspaces w ON w.id=m.workspace_id
            JOIN users u ON u.id=m.user_id
            WHERE m.workspace_id=?
            AND m.user_id=?
            AND m.active
            AND u.enabled
            """,
            (r, n) ->
                new Membership(
                    workspace,
                    actor,
                    WorkspaceRole.valueOf(r.getString("role")),
                    ZoneId.of(r.getString("time_zone"))),
            workspace,
            actor)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Room> room(UUID workspace, UUID room) {
    return sql
        .query("SELECT * FROM rooms WHERE workspace_id=? AND id=?", JdbcRows::room, workspace, room)
        .stream()
        .findFirst();
  }

  @Override
  public IdempotencyEntry lockIdempotency(IdempotencyScope scope, String hash) {
    sql.update(
        """
            INSERT INTO idempotency_records(id,workspace_id,actor_id,operation,request_key,request_hash) VALUES(?,?,?,?,?,?) ON CONFLICT(workspace_id,actor_id,operation,request_key) DO NOTHING
            """,
        UUID.randomUUID(),
        scope.workspaceId(),
        scope.actorId(),
        scope.operation().name(),
        scope.key(),
        hash);
    return sql.queryForObject(
        """
            SELECT id,request_hash,reservation_id
            FROM idempotency_records
            WHERE workspace_id=?
            AND actor_id=?
            AND operation=?
            AND request_key=? FOR UPDATE
            """,
        (r, n) ->
            new IdempotencyEntry(
                JdbcRows.uuid(r, "id"),
                scope,
                r.getString("request_hash"),
                JdbcRows.uuid(r, "reservation_id")),
        scope.workspaceId(),
        scope.actorId(),
        scope.operation().name(),
        scope.key());
  }

  @Override
  public void lockActor(UUID workspace, UUID actor) {
    sql.update(
        "INSERT INTO actor_booking_guards(workspace_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",
        workspace,
        actor);
    sql.queryForObject(
        "SELECT user_id FROM actor_booking_guards WHERE workspace_id=? AND user_id=? FOR UPDATE",
        UUID.class,
        workspace,
        actor);
  }

  @Override
  public SlotSnapshot lockSlot(UUID workspace, UUID room, Instant start) {
    var slots =
        sql.query(
            """
            SELECT s.id,s.assigned_reservation_id
            FROM rooms r
            JOIN slots s ON s.workspace_id=r.workspace_id
            AND s.room_id=r.id
            WHERE r.workspace_id=?
            AND r.id=?
            AND s.start_at=?
            FOR UPDATE OF r,s
            """,
            (r, n) ->
                new UUID[] {JdbcRows.uuid(r, "id"), JdbcRows.uuid(r, "assigned_reservation_id")},
            workspace,
            room,
            JdbcRows.timestamp(start));
    if (slots.isEmpty()) throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
    UUID[] row = slots.getFirst();
    Reservation assigned =
        row[1] == null
            ? null
            : sql.queryForObject(
                "SELECT * FROM reservations WHERE workspace_id=? AND id=?",
                JdbcRows::reservation,
                workspace,
                row[1]);
    return new SlotSnapshot(row[0], room(workspace, room).orElseThrow(), start, assigned);
  }

  @Override
  public Optional<Reservation> ownedReservation(UUID workspace, UUID reservation, UUID owner) {
    return sql
        .query(
            "SELECT * FROM reservations WHERE workspace_id=? AND id=? AND owner_id=?",
            JdbcRows::reservation,
            workspace,
            reservation,
            owner)
        .stream()
        .findFirst();
  }

  @Override
  public Reservation lockReservation(UUID workspace, UUID reservation, UUID owner) {
    return sql
        .query(
            "SELECT * FROM reservations WHERE workspace_id=? AND id=? AND owner_id=? FOR UPDATE",
            JdbcRows::reservation,
            workspace,
            reservation,
            owner)
        .stream()
        .findFirst()
        .orElseThrow(() -> new RuleViolation(ProblemCode.NOT_FOUND));
  }

  @Override
  public Instant now() {
    return clock.now();
  }

  @Override
  public int activeHolds(UUID workspace, UUID actor, Instant now) {
    return sql.queryForObject(
        "SELECT count(*) FROM reservations WHERE workspace_id=? AND owner_id=? AND status='HELD' AND expires_at>?",
        Integer.class,
        workspace,
        actor,
        JdbcRows.timestamp(now));
  }

  @Override
  public int confirmations(UUID workspace, UUID actor, LocalDate date, ZoneId zone) {
    return sql.queryForObject(
        "SELECT count(*) FROM reservations WHERE workspace_id=? AND owner_id=? AND confirmed_at IS NOT NULL AND start_at>=? AND start_at<?",
        Integer.class,
        workspace,
        actor,
        JdbcRows.timestamp(date.atStartOfDay(zone).toInstant()),
        JdbcRows.timestamp(date.plusDays(1).atStartOfDay(zone).toInstant()));
  }

  @Override
  public void save(Reservation value) {
    int changed =
        sql.update(
            """
            INSERT INTO reservations(id,workspace_id,room_id,slot_id,owner_id,start_at,end_at,status,created_at,expires_at,confirmed_at) SELECT ?,?,?,s.id,?,?,?,?,?,?,?
            FROM slots s
            WHERE s.workspace_id=?
            AND s.room_id=?
            AND s.start_at=? ON CONFLICT(id)
            DO UPDATE SET status=EXCLUDED.status,confirmed_at=EXCLUDED.confirmed_at
            WHERE reservations.workspace_id=EXCLUDED.workspace_id
            AND reservations.room_id=EXCLUDED.room_id
            AND reservations.owner_id=EXCLUDED.owner_id
            AND reservations.start_at=EXCLUDED.start_at
            AND reservations.created_at=EXCLUDED.created_at
            AND reservations.expires_at=EXCLUDED.expires_at
            """,
            value.id(),
            value.workspaceId(),
            value.roomId(),
            value.ownerId(),
            JdbcRows.timestamp(value.startAt()),
            JdbcRows.timestamp(value.endAt()),
            value.status().name(),
            JdbcRows.timestamp(value.createdAt()),
            JdbcRows.timestamp(value.expiresAt()),
            JdbcRows.timestamp(value.confirmedAt()),
            value.workspaceId(),
            value.roomId(),
            JdbcRows.timestamp(value.startAt()));
    if (changed != 1) throw new IllegalStateException("Reservation persistence invariant failed.");
  }

  @Override
  public void assignSlot(UUID workspace, UUID slot, UUID reservation) {
    if (sql.update(
            "UPDATE slots SET assigned_reservation_id=? WHERE workspace_id=? AND id=? AND assigned_reservation_id IS NULL",
            reservation,
            workspace,
            slot)
        != 1) throw new IllegalStateException("Slot assignment invariant failed.");
  }

  @Override
  public void releaseSlot(UUID workspace, UUID slot, UUID expected) {
    if (sql.update(
            "UPDATE slots SET assigned_reservation_id=NULL WHERE workspace_id=? AND id=? AND assigned_reservation_id=?",
            workspace,
            slot,
            expected)
        != 1) throw new IllegalStateException("Slot release invariant failed.");
  }

  @Override
  public void completeIdempotency(IdempotencyEntry entry, UUID reservation) {
    if (sql.update(
            """
            UPDATE idempotency_records i
            SET reservation_id=?,retained_until=greatest(i.retained_until,r.end_at)
            FROM reservations r
            WHERE i.id=?
            AND i.workspace_id=?
            AND i.actor_id=?
            AND i.reservation_id IS NULL
            AND r.id=?
            AND r.workspace_id=i.workspace_id
            AND r.owner_id=i.actor_id
            """,
            reservation,
            entry.id(),
            entry.scope().workspaceId(),
            entry.scope().actorId(),
            reservation)
        != 1) throw new IllegalStateException("Retry persistence invariant failed.");
  }

  @Override
  public void append(BookingEvent event) {
    sql.update(
        "INSERT INTO booking_events(id,workspace_id,kind,reservation_id,actor_id,room_id,occurred_at) VALUES(?,?,?,?,?,?,?)",
        event.id(),
        event.workspaceId(),
        event.kind().name(),
        event.reservationId(),
        event.actorId(),
        event.roomId(),
        JdbcRows.timestamp(event.occurredAt()));
  }
}
