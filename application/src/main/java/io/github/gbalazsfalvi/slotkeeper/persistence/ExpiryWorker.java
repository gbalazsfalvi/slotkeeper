package io.github.gbalazsfalvi.slotkeeper.persistence;

import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Safe bounded cleanup; concurrent app processes skip busy slots instead of stealing assignments.
 */
public final class ExpiryWorker {
  private final JdbcTemplate sql;
  private final DatabaseTime clock;
  private final TransactionTemplate transactions;

  /**
   * Creates a database-coordinated maintenance worker.
   *
   * @param source runtime-role connection source
   */
  public ExpiryWorker(DataSource source) {
    this(source, new DatabaseTime(source));
  }

  /**
   * Shares independent time observations without retaining the clock row's lock in cleanup work.
   *
   * @param source primary runtime connections
   * @param clock authoritative nondecreasing decision time
   */
  public ExpiryWorker(DataSource source, DatabaseTime clock) {
    this.clock = clock;
    sql = new JdbcTemplate(source);
    transactions = new TransactionTemplate(new JdbcTransactionManager(source));
    transactions.setTimeout(15);
  }

  /**
   * Expires at most fifty assigned holds, with fresh time after slot and reservation locks.
   *
   * @return number of applied expiry transitions
   */
  public int expireBatch() {
    Instant observed = clock.now();
    List<UUID[]> candidates =
        sql.query(
            """
            SELECT s.workspace_id,s.id,s.assigned_reservation_id,s.room_id
            FROM slots s
            JOIN reservations r ON r.id=s.assigned_reservation_id
            AND r.workspace_id=s.workspace_id
            WHERE r.status='HELD'
            AND r.expires_at<=?
            ORDER BY r.expires_at
            LIMIT 50
            """,
            (r, n) ->
                new UUID[] {
                  JdbcRows.uuid(r, "workspace_id"),
                  JdbcRows.uuid(r, "id"),
                  JdbcRows.uuid(r, "assigned_reservation_id"),
                  JdbcRows.uuid(r, "room_id")
                },
            JdbcRows.timestamp(observed));
    int expired = 0;
    for (UUID[] candidate : candidates) {
      Boolean changed =
          transactions.execute(
              status -> {
                sql.execute("SET LOCAL lock_timeout='5s'");
                // Match booking's room-before-slot order. The event FK also needs this room;
                // locking a slot first could otherwise form a room/slot cycle with a new hold.
                if (sql.query(
                        "SELECT id FROM rooms WHERE workspace_id=? AND id=? FOR UPDATE SKIP LOCKED",
                        (r, n) -> JdbcRows.uuid(r, "id"),
                        candidate[0],
                        candidate[3])
                    .isEmpty()) return false;
                var assignment =
                    sql.query(
                        "SELECT assigned_reservation_id FROM slots WHERE workspace_id=? AND id=? FOR UPDATE SKIP LOCKED",
                        (r, n) -> JdbcRows.uuid(r, "assigned_reservation_id"),
                        candidate[0],
                        candidate[1]);
                if (assignment.isEmpty() || !candidate[2].equals(assignment.getFirst()))
                  return false;
                Reservation old =
                    sql.queryForObject(
                        "SELECT * FROM reservations WHERE workspace_id=? AND id=? FOR UPDATE",
                        JdbcRows::reservation,
                        candidate[0],
                        candidate[2]);
                JdbcBookingTransaction tx = new JdbcBookingTransaction(sql, clock);
                Instant now = tx.now();
                if (old.status() != ReservationStatus.HELD || old.expiresAt().isAfter(now))
                  return false;
                tx.save(old.expire(now));
                tx.releaseSlot(candidate[0], candidate[1], old.id());
                tx.append(
                    new BookingEvent(
                        UUID.randomUUID(),
                        old.workspaceId(),
                        BookingEvent.Kind.EXPIRED,
                        old.id(),
                        old.ownerId(),
                        old.roomId(),
                        now));
                return true;
              });
      if (Boolean.TRUE.equals(changed)) expired++;
    }
    return expired;
  }
}
