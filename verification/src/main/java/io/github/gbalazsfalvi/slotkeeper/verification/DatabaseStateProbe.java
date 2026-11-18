package io.github.gbalazsfalvi.slotkeeper.verification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Read-only independent aggregate queries against the actual owned Compose PostgreSQL database. */
final class DatabaseStateProbe {
  private static final String QUERY =
      """
      WITH probe AS MATERIALIZED (
        SELECT last_observed AS checked_at FROM slotkeeper.decision_clock WHERE id = 1
      ),
      active_reservations AS MATERIALIZED (
        SELECT r.* FROM slotkeeper.reservations r CROSS JOIN probe p
        WHERE r.status = 'CONFIRMED' OR (r.status = 'HELD' AND r.expires_at > p.checked_at)
      )
      SELECT
        p.checked_at,
        current_setting('server_version_num')::integer AS server_version_num,
        current_setting('transaction_read_only')::boolean AS transaction_read_only,
        (current_database() = 'slotkeeper' AND current_user = 'slotkeeper_app'
          AND current_schema() = 'slotkeeper'
          AND current_setting('transaction_isolation') = 'repeatable read') AS expected_database_context,
        (SELECT count(*) FROM active_reservations r WHERE NOT EXISTS (
          SELECT 1 FROM slotkeeper.slots s WHERE s.id = r.slot_id
            AND s.workspace_id = r.workspace_id AND s.room_id = r.room_id
            AND s.start_at = r.start_at AND s.end_at = r.end_at
            AND s.assigned_reservation_id = r.id
        )) AS active_reservation_without_matching_assignment,
        (SELECT count(*) FROM slotkeeper.slots s
          WHERE s.assigned_reservation_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM slotkeeper.reservations r WHERE r.id = s.assigned_reservation_id
              AND r.workspace_id = s.workspace_id AND r.slot_id = s.id AND r.room_id = s.room_id
              AND r.start_at = s.start_at AND r.end_at = s.end_at
        )) AS invalid_assignment_scope,
        (SELECT count(*) FROM (
          SELECT workspace_id, slot_id FROM active_reservations
          GROUP BY workspace_id, slot_id HAVING count(*) > 1
        ) invalid) AS duplicate_active_per_slot,
        (SELECT count(*) FROM (
          SELECT workspace_id, owner_id FROM active_reservations WHERE status = 'HELD'
          GROUP BY workspace_id, owner_id HAVING count(*) > 2
        ) invalid) AS actors_over_two_holds,
        (SELECT count(*) FROM (
          SELECT r.workspace_id, r.owner_id, (r.start_at AT TIME ZONE w.time_zone)::date
          FROM slotkeeper.reservations r
          JOIN slotkeeper.workspaces w ON w.id = r.workspace_id
          WHERE r.confirmed_at IS NOT NULL
          GROUP BY r.workspace_id, r.owner_id, (r.start_at AT TIME ZONE w.time_zone)::date
          HAVING count(*) > 4
        ) invalid) AS actors_over_four_confirmations_per_local_date,
        (SELECT count(*) FROM slotkeeper.reservations r WHERE NOT EXISTS (
          SELECT 1 FROM slotkeeper.booking_events e WHERE e.reservation_id = r.id
            AND e.workspace_id = r.workspace_id AND e.actor_id = r.owner_id
            AND e.room_id = r.room_id AND e.kind = 'HOLD_CREATED'
        )) AS reservations_missing_created_event,
        (SELECT count(*) FROM slotkeeper.reservations r
          WHERE r.created_at > p.checked_at - interval '30 days' AND NOT EXISTS (
            SELECT 1 FROM slotkeeper.idempotency_records i WHERE i.reservation_id = r.id
              AND i.workspace_id = r.workspace_id AND i.actor_id = r.owner_id
              AND i.operation = 'CREATE_HOLD'
        )) AS recent_reservations_missing_create_receipt,
        (has_table_privilege(current_user, 'slotkeeper.booking_events', 'UPDATE')
          OR has_any_column_privilege(current_user, 'slotkeeper.booking_events', 'UPDATE')) AS runtime_can_update_events,
        has_table_privilege(current_user, 'slotkeeper.booking_events', 'DELETE') AS runtime_can_delete_events,
        (has_database_privilege(current_user, current_database(), 'CREATE')
          OR has_schema_privilege(current_user, 'slotkeeper', 'CREATE')) AS runtime_can_create_schema
      FROM probe p
      """;

  private final Connections connections;

  DatabaseStateProbe() {
    this(DriverManager::getConnection);
  }

  DatabaseStateProbe(Connections connections) {
    this.connections = connections;
  }

  ScenarioResult check(DatabaseTarget target, String password) {
    if (target == null)
      return ScenarioResult.incomplete(CheckId.S02, Reason.DATABASE_TARGET_MISSING);
    if (password == null || password.isBlank())
      return ScenarioResult.incomplete(CheckId.S02, Reason.DATABASE_PASSWORD_MISSING);
    try (var connection = connections.open(target.url(), target.connectionProperties(password))) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      DatabaseStateObservation observation;
      try (var statement = connection.prepareStatement(QUERY)) {
        statement.setQueryTimeout(10);
        statement.setMaxRows(1);
        statement.setFetchSize(1);
        try (var result = statement.executeQuery()) {
          if (!result.next()
              || !requiredBoolean(result, "expected_database_context")
              || !requiredBoolean(result, "transaction_read_only"))
            throw new SQLException("Read-only database context unavailable");
          observation = project(result);
        }
      }
      connection.rollback();
      var assertions = observation.assertions();
      boolean passed = assertions.stream().allMatch(StateAssertion::passed);
      return new ScenarioResult(
          CheckId.S02,
          passed ? Outcome.PASS : Outcome.FAIL,
          passed ? Reason.OBSERVED : Reason.ASSERTION_FAILED,
          List.of(),
          assertions,
          Map.of(),
          observation);
    } catch (SQLException | RuntimeException exception) {
      return new ScenarioResult(
          CheckId.S02,
          Outcome.ERROR,
          Reason.DATABASE_PROBE_UNAVAILABLE,
          List.of(),
          List.of(),
          Map.of());
    }
  }

  private static DatabaseStateObservation project(ResultSet result) throws SQLException {
    OffsetDateTime checked = result.getObject("checked_at", OffsetDateTime.class);
    if (checked == null) throw new SQLException("Database time unavailable");
    int version = result.getInt("server_version_num");
    if (result.wasNull()) throw new SQLException("Database version unavailable");
    return new DatabaseStateObservation(
        requiredLong(result, "active_reservation_without_matching_assignment"),
        requiredLong(result, "invalid_assignment_scope"),
        requiredLong(result, "duplicate_active_per_slot"),
        requiredLong(result, "actors_over_two_holds"),
        requiredLong(result, "actors_over_four_confirmations_per_local_date"),
        requiredLong(result, "reservations_missing_created_event"),
        requiredLong(result, "recent_reservations_missing_create_receipt"),
        requiredBoolean(result, "runtime_can_update_events"),
        requiredBoolean(result, "runtime_can_delete_events"),
        requiredBoolean(result, "runtime_can_create_schema"),
        true,
        version,
        checked.toInstant());
  }

  private static long requiredLong(ResultSet result, String column) throws SQLException {
    long value = result.getLong(column);
    if (result.wasNull()) throw new SQLException("Aggregate observation unavailable");
    return value;
  }

  private static boolean requiredBoolean(ResultSet result, String column) throws SQLException {
    boolean value = result.getBoolean(column);
    if (result.wasNull()) throw new SQLException("Privilege observation unavailable");
    return value;
  }

  @FunctionalInterface
  interface Connections {
    Connection open(String url, Properties properties) throws SQLException;
  }
}
