package io.github.gbalazsfalvi.slotkeeper.persistence;

import io.github.gbalazsfalvi.slotkeeper.core.*;
import io.github.gbalazsfalvi.slotkeeper.core.identity.Actor;
import io.github.gbalazsfalvi.slotkeeper.core.workspace.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Scoped room administration with a serialized twenty-room resource ceiling. */
@Component
public final class JdbcRoomAdministration {
  private final JdbcTemplate sql;
  private final DatabaseTime clock;
  private final TransactionTemplate transactions;

  /**
   * Creates scoped transactional room administration.
   *
   * @param source runtime-role connections
   * @param clock independent authoritative booking time
   */
  public JdbcRoomAdministration(DataSource source, DatabaseTime clock) {
    this.clock = clock;
    sql = new JdbcTemplate(source);
    transactions = new TransactionTemplate(new JdbcTransactionManager(source));
    transactions.setTimeout(15);
  }

  /**
   * Creates an authorized room and its finite seven-day schedule atomically.
   *
   * @param actor trusted subject
   * @param workspace scoped workspace
   * @param name bounded plain display text
   * @param description bounded plain descriptive text
   * @return committed room
   */
  public Room create(Actor actor, UUID workspace, String name, String description) {
    return transactions.execute(
        status -> {
          Membership member = admin(actor, workspace);
          String cleanName = text(name, 80, true), cleanDescription = text(description, 500, false);
          sql.execute("SET LOCAL lock_timeout='5s'");
          sql.update(
              "INSERT INTO workspace_room_guards(workspace_id) VALUES(?) ON CONFLICT DO NOTHING",
              workspace);
          sql.queryForObject(
              "SELECT workspace_id FROM workspace_room_guards WHERE workspace_id=? FOR UPDATE",
              UUID.class,
              workspace);
          if (sql.queryForObject(
                  "SELECT count(*) FROM rooms WHERE workspace_id=?", Integer.class, workspace)
              >= 20) throw new RuleViolation(ProblemCode.QUOTA_EXCEEDED);
          UUID id = UUID.randomUUID();
          sql.update(
              "INSERT INTO rooms(id,workspace_id,name,description) VALUES(?,?,?,?)",
              id,
              workspace,
              cleanName,
              cleanDescription);
          LocalDate today = clock.now().atZone(member.timeZone()).toLocalDate();
          for (int day = 0; day < 7; day++)
            for (int half = 0; half < 16; half++) {
              Instant start =
                  today
                      .plusDays(day)
                      .atTime(9, 0)
                      .plusMinutes(half * 30L)
                      .atZone(member.timeZone())
                      .toInstant();
              sql.update(
                  "INSERT INTO slots(id,workspace_id,room_id,start_at,end_at) VALUES(?,?,?,?,?)",
                  UUID.randomUUID(),
                  workspace,
                  id,
                  JdbcRows.timestamp(start),
                  JdbcRows.timestamp(start.plusSeconds(1800)));
            }
          return new Room(id, workspace, cleanName, cleanDescription, true);
        });
  }

  /**
   * Updates only writable room fields; closing admission leaves existing reservations untouched.
   *
   * @param actor trusted subject
   * @param workspace scoped workspace
   * @param roomId scoped room identity
   * @param name optional new bounded name
   * @param description optional new bounded description
   * @param enabled optional admission flag
   * @return committed room
   */
  public Room patch(
      Actor actor, UUID workspace, UUID roomId, String name, String description, Boolean enabled) {
    return transactions.execute(
        status -> {
          admin(actor, workspace);
          if (name == null && description == null && enabled == null)
            throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
          sql.execute("SET LOCAL lock_timeout='5s'");
          Room old =
              sql
                  .query(
                      "SELECT * FROM rooms WHERE workspace_id=? AND id=? FOR UPDATE",
                      JdbcRows::room,
                      workspace,
                      roomId)
                  .stream()
                  .findFirst()
                  .orElseThrow(() -> new RuleViolation(ProblemCode.NOT_FOUND));
          Room changed =
              new Room(
                  roomId,
                  workspace,
                  name == null ? old.name() : text(name, 80, true),
                  description == null ? old.description() : text(description, 500, false),
                  enabled == null ? old.enabled() : enabled);
          sql.update(
              "UPDATE rooms SET name=?,description=?,enabled=? WHERE workspace_id=? AND id=?",
              changed.name(),
              changed.description(),
              changed.enabled(),
              workspace,
              roomId);
          return changed;
        });
  }

  private Membership admin(Actor actor, UUID workspace) {
    Membership member =
        new JdbcBookingTransaction(sql, clock)
            .membership(workspace, actor.userId())
            .orElseThrow(() -> new RuleViolation(ProblemCode.NOT_FOUND));
    member.requireAdmin();
    return member;
  }

  private static String text(String value, int limit, boolean required) {
    if (value == null) throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
    String clean = value.strip();
    if (clean.length() > limit
        || (required && clean.isEmpty())
        || clean.codePoints().anyMatch(Character::isISOControl))
      throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
    return clean;
  }
}
