package io.github.gbalazsfalvi.slotkeeper.persistence;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly synthetic local fixtures. This initializer is never a production identity importer.
 */
public final class DemoSeed {
  private DemoSeed() {}

  /**
   * Derives stable fixture identities without real personal data.
   *
   * @param label public synthetic label
   * @return deterministic fixture identity, not an authentication secret
   */
  public static UUID id(String label) {
    return UUID.nameUUIDFromBytes(("slotkeeper:demo:" + label).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Adds missing local demo accounts and rooms, leaving existing bookings untouched.
   *
   * @param source migration-role connection, scoped to the Slotkeeper schema
   */
  public static void populate(DataSource source) {
    JdbcTemplate sql = new JdbcTemplate(source);
    String hash = new Argon2PasswordEncoder(16, 32, 1, 19456, 2).encode("Slotkeeper-Local-Only!");
    new TransactionTemplate(new JdbcTransactionManager(source))
        .executeWithoutResult(
            status -> {
              for (String name : new String[] {"alex", "morgan", "riley", "jordan"}) {
                sql.update(
                    "INSERT INTO users(id,username,display_name,password_hash) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                    id(name),
                    name,
                    Character.toUpperCase(name.charAt(0)) + name.substring(1),
                    hash);
              }
              sql.update(
                  "INSERT INTO workspaces(id,name,time_zone) VALUES(?,?,'Europe/Budapest'),(?,?,'Europe/Budapest') ON CONFLICT DO NOTHING",
                  id("harbor"),
                  "Harbor Works",
                  id("studio"),
                  "Studio North");
              membership(sql, "harbor", "alex", "MEMBER");
              membership(sql, "harbor", "morgan", "WORKSPACE_ADMIN");
              membership(sql, "studio", "riley", "MEMBER");
              membership(sql, "studio", "jordan", "WORKSPACE_ADMIN");
              membership(sql, "harbor", "jordan", "MEMBER");
              room(sql, "harbor", "harbor-oak", "Oak", "A quiet room for focused conversations.");
              room(sql, "harbor", "harbor-maple", "Maple", "A bright space for planning together.");
              room(sql, "studio", "studio-willow", "Willow", "A private room at Studio North.");
              provisionSlots(sql);
            });
  }

  private static void membership(JdbcTemplate sql, String workspace, String user, String role) {
    sql.update(
        "INSERT INTO workspace_memberships(workspace_id,user_id,role) VALUES(?,?,?) ON CONFLICT DO NOTHING",
        id(workspace),
        id(user),
        role);
  }

  private static void room(
      JdbcTemplate sql, String workspace, String key, String name, String description) {
    sql.update(
        "INSERT INTO rooms(id,workspace_id,name,description) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
        id(key),
        id(workspace),
        name,
        description);
  }

  /**
   * Extends the finite rolling schedule using database time and named workspace zones.
   *
   * @param sql runtime connection; inserts only absent slots and never changes assignments
   */
  public static void provisionSlots(JdbcTemplate sql) {
    provisionSlots(sql, new DatabaseTime(sql.getDataSource()));
  }

  /**
   * Extends the calendar without retaining a clock lock across room/slot work.
   *
   * @param sql scoped runtime connection
   * @param clock shared independent logical time
   */
  public static void provisionSlots(JdbcTemplate sql, DatabaseTime clock) {
    Instant now = clock.now();
    sql.query(
        "SELECT r.id,r.workspace_id,w.time_zone FROM rooms r JOIN workspaces w ON w.id=r.workspace_id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            row -> {
              ZoneId zone = ZoneId.of(row.getString("time_zone"));
              LocalDate today = now.atZone(zone).toLocalDate();
              UUID room = row.getObject("id", UUID.class),
                  workspace = row.getObject("workspace_id", UUID.class);
              for (int day = 0; day < 7; day++)
                for (int halfHour = 0; halfHour < 16; halfHour++) {
                  Instant start =
                      today
                          .plusDays(day)
                          .atTime(9, 0)
                          .plusMinutes(halfHour * 30L)
                          .atZone(zone)
                          .toInstant();
                  sql.update(
                      "INSERT INTO slots(id,workspace_id,room_id,start_at,end_at) VALUES(?,?,?,?,?) ON CONFLICT(workspace_id,room_id,start_at) DO NOTHING",
                      UUID.randomUUID(),
                      workspace,
                      room,
                      JdbcRows.timestamp(start),
                      JdbcRows.timestamp(start.plusSeconds(1800)));
                }
            });
  }
}
