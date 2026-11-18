package io.github.gbalazsfalvi.slotkeeper.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseStateProbeTest {
  private static final String URL =
      "jdbc:postgresql://127.0.0.1:55419/slotkeeper?currentSchema=slotkeeper";
  @TempDir Path directory;

  @Test
  void onlyOwnedDatabaseAndBoundedConnectionOptionsAreAdmitted() {
    assertDoesNotThrow(() -> new DatabaseTarget(URL));
    assertDoesNotThrow(() -> new DatabaseTarget(URL + "&connectTimeout=3&socketTimeout=12"));
    for (String invalid :
        List.of(
            URL.replace("127.0.0.1", "localhost"),
            URL.replace("55419", "5432"),
            URL.replace("/slotkeeper?", "/other?"),
            URL.replace("currentSchema=slotkeeper", "currentSchema=public"),
            URL + "&password=private-password",
            URL + "&user=postgres",
            URL + "&options=-csearch_path=public",
            URL + "&socketFactory=arbitrary",
            URL + "&connectTimeout=0",
            URL + "&connectTimeout=11",
            URL + "&socketTimeout=31",
            URL + "&currentSchema=slotkeeper",
            "jdbc:postgresql://user:password@127.0.0.1:55419/slotkeeper?currentSchema=slotkeeper")) {
      var exception =
          assertThrows(IllegalArgumentException.class, () -> new DatabaseTarget(invalid));
      assertFalse(exception.getMessage().contains("private-password"));
    }
  }

  @Test
  void cliAcceptsOnlyTheValidatedDatabaseUrlAndHasNoPasswordOption() {
    String[] args = {
      "--app",
      "http://127.0.0.1:4190",
      "--app-instance",
      "df5c1ce3-36be-4736-ac91-fd1c9a139949",
      "--output",
      directory.toString(),
      "--revision",
      "abcdef0",
      "--database-url",
      URL
    };
    assertEquals(URL, RehearsalOptions.parse(args).databaseTarget().url());
    args[args.length - 1] = URL + "&password=private-password";
    assertThrows(IllegalArgumentException.class, () -> RehearsalOptions.parse(args));
    args[args.length - 2] = "--database-password";
    args[args.length - 1] = "private-password";
    assertThrows(IllegalArgumentException.class, () -> RehearsalOptions.parse(args));
  }

  @Test
  void rejectedApplicationMarkerPreventsCredentialReadAndDatabaseConnection() throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 2);
    server.createContext(
        "/api/instance",
        exchange -> {
          try (exchange) {
            byte[] body =
                "{\"project\":\"Slotkeeper\",\"instanceId\":\"df5c1ce3-36be-4736-ac91-fd1c9a139949\",\"mode\":\"LOCAL\",\"version\":\"0.1.0\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    try {
      int port = server.getAddress().getPort();
      var app =
          new OwnedTarget(
              java.net.URI.create("http://127.0.0.1:" + port),
              java.util.UUID.randomUUID(),
              OwnedTarget.Kind.PRODUCT,
              port);
      var options =
          new RehearsalOptions(
              app, null, null, directory, "abcdef0", null, new DatabaseTarget(URL));
      var probe =
          new DatabaseStateProbe(
              (url, properties) -> {
                fail("Rejected target must not access database");
                throw new SQLException();
              });
      var report =
          new ScenarioRunner(
                  options,
                  probe,
                  () -> {
                    fail("Rejected target must not read credentials");
                    return "private-password";
                  })
              .run();
      assertEquals(15, report.results().size());
      assertEquals(
          Outcome.INCOMPLETE,
          report.results().stream()
              .filter(r -> r.id() == CheckId.S02)
              .findFirst()
              .orElseThrow()
              .outcome());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void missingDatabaseOrEnvironmentPasswordNeverConnectsAndRemainsInFifteenCaseExport()
      throws Exception {
    var probe =
        new DatabaseStateProbe(
            (url, properties) -> {
              fail("Missing prerequisites must not connect");
              throw new SQLException();
            });
    var absent = probe.check(null, "private-password");
    var noPassword = probe.check(new DatabaseTarget(URL), null);
    assertEquals(Outcome.INCOMPLETE, absent.outcome());
    assertEquals(Outcome.INCOMPLETE, noPassword.outcome());
    new EvidenceExporter()
        .write(
            EvidenceReport.of(
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, "abcdef0", List.of(noPassword)),
            directory);
    var report =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(Files.readString(directory.resolve("report.json")));
    assertEquals(15, report.path("results").size());
    assertEquals(15, report.path("counts").path("INCOMPLETE").asInt());
    assertFalse(report.path("complete").asBoolean());
  }

  @Test
  void jdbcProjectionUsesReadOnlyRepeatableReadRollbackAndExportsTypedSnapshot() throws Exception {
    var jdbc = new JdbcProjection(healthyRow());
    var probe =
        new DatabaseStateProbe(
            (url, properties) -> {
              assertEquals(URL, url);
              assertEquals("slotkeeper_app", properties.getProperty("user"));
              assertEquals("private-password", properties.getProperty("password"));
              assertEquals("5", properties.getProperty("connectTimeout"));
              assertEquals("20", properties.getProperty("socketTimeout"));
              assertEquals("transaction", properties.getProperty("readOnlyMode"));
              return jdbc.connection();
            });
    var result = probe.check(new DatabaseTarget(URL), "private-password");
    assertEquals(Outcome.PASS, result.outcome());
    assertTrue(jdbc.rolledBack);
    assertTrue(jdbc.closed);
    assertEquals(180007, result.databaseState().serverVersionNum());
    assertEquals(
        java.time.Instant.parse("2026-11-18T12:00:00Z"), result.databaseState().checkedAtUTC());
    new EvidenceExporter()
        .write(
            EvidenceReport.of(
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, "abcdef0", List.of(result)),
            directory);
    for (String name : List.of("report.json", "report.md", "report.html")) {
      String exported = Files.readString(directory.resolve(name));
      assertTrue(exported.contains("activeReservationWithoutMatchingAssignment"));
      assertTrue(exported.contains("runtimeCanCreateSchema"));
      assertTrue(exported.contains("180007"));
      assertFalse(exported.contains("private-password"));
    }
  }

  @Test
  void everyViolatedCounterOrUnexpectedWritePrivilegeFailsItsStateCheck() {
    for (String name :
        List.of(
            "active_reservation_without_matching_assignment",
            "invalid_assignment_scope",
            "duplicate_active_per_slot",
            "actors_over_two_holds",
            "actors_over_four_confirmations_per_local_date",
            "reservations_missing_created_event",
            "recent_reservations_missing_create_receipt",
            "runtime_can_update_events",
            "runtime_can_delete_events",
            "runtime_can_create_schema")) {
      var row = healthyRow();
      row.put(name, name.startsWith("runtime_") ? Boolean.TRUE : 1L);
      var jdbc = new JdbcProjection(row);
      var result =
          new DatabaseStateProbe((url, properties) -> jdbc.connection())
              .check(new DatabaseTarget(URL), "private-password");
      assertEquals(Outcome.FAIL, result.outcome(), name);
      assertEquals(1, result.assertions().stream().filter(a -> !a.passed()).count(), name);
      assertNotNull(result.databaseState());
    }
  }

  @Test
  void nullProjectionOrUnsafeTransactionAndSqlErrorsCannotBecomePassOrLeakDetails()
      throws Exception {
    for (String name :
        List.of(
            "active_reservation_without_matching_assignment",
            "runtime_can_delete_events",
            "checked_at",
            "expected_database_context",
            "transaction_read_only")) {
      var row = healthyRow();
      row.put(
          name,
          name.equals("expected_database_context") || name.equals("transaction_read_only")
              ? Boolean.FALSE
              : null);
      var jdbc = new JdbcProjection(row);
      assertEquals(
          Outcome.ERROR,
          new DatabaseStateProbe((url, properties) -> jdbc.connection())
              .check(new DatabaseTarget(URL), "private-password")
              .outcome(),
          name);
    }
    var error =
        new DatabaseStateProbe(
                (url, properties) -> {
                  throw new SQLException("private-password raw SQL/internal rows");
                })
            .check(new DatabaseTarget(URL), "private-password");
    assertEquals(Outcome.ERROR, error.outcome());
    new EvidenceExporter()
        .write(
            EvidenceReport.of(
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, "abcdef0", List.of(error)),
            directory);
    for (String name : List.of("report.json", "report.md", "report.html")) {
      String exported = Files.readString(directory.resolve(name));
      assertFalse(exported.contains("private-password"));
      assertFalse(exported.contains("raw SQL/internal rows"));
    }
  }

  private static Map<String, Object> healthyRow() {
    Map<String, Object> row = new HashMap<>();
    for (String name :
        List.of(
            "active_reservation_without_matching_assignment",
            "invalid_assignment_scope",
            "duplicate_active_per_slot",
            "actors_over_two_holds",
            "actors_over_four_confirmations_per_local_date",
            "reservations_missing_created_event",
            "recent_reservations_missing_create_receipt")) row.put(name, 0L);
    row.put("runtime_can_update_events", false);
    row.put("runtime_can_delete_events", false);
    row.put("runtime_can_create_schema", false);
    row.put("server_version_num", 180007);
    row.put("checked_at", OffsetDateTime.parse("2026-11-18T12:00:00Z"));
    row.put("expected_database_context", true);
    row.put("transaction_read_only", true);
    return row;
  }

  /** JDBC column projection double only; it does not execute SQL or claim PostgreSQL evidence. */
  private static final class JdbcProjection {
    private final Map<String, Object> row;
    private boolean readOnly;
    private int isolation;
    private boolean autoCommit = true;
    private boolean rolledBack;
    private boolean closed;

    JdbcProjection(Map<String, Object> row) {
      this.row = row;
    }

    Connection connection() {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "setReadOnly" -> {
                      readOnly = (Boolean) args[0];
                      yield null;
                    }
                    case "setTransactionIsolation" -> {
                      isolation = (Integer) args[0];
                      yield null;
                    }
                    case "setAutoCommit" -> {
                      autoCommit = (Boolean) args[0];
                      yield null;
                    }
                    case "prepareStatement" -> {
                      assertTrue(readOnly);
                      assertFalse(autoCommit);
                      assertEquals(Connection.TRANSACTION_REPEATABLE_READ, isolation);
                      yield statement();
                    }
                    case "rollback" -> {
                      rolledBack = true;
                      yield null;
                    }
                    case "close" -> {
                      closed = true;
                      yield null;
                    }
                    default ->
                        throw new AssertionError("Unexpected JDBC operation: " + method.getName());
                  });
    }

    private PreparedStatement statement() {
      return (PreparedStatement)
          Proxy.newProxyInstance(
              PreparedStatement.class.getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "setQueryTimeout", "setMaxRows", "setFetchSize", "close" -> null;
                    case "executeQuery" -> resultSet();
                    default ->
                        throw new AssertionError(
                            "Unexpected statement operation: " + method.getName());
                  });
    }

    private ResultSet resultSet() {
      var next = new AtomicBoolean(true);
      var wasNull = new AtomicBoolean(false);
      return (ResultSet)
          Proxy.newProxyInstance(
              ResultSet.class.getClassLoader(),
              new Class<?>[] {ResultSet.class},
              (proxy, method, args) -> {
                if (method.getName().equals("next")) return next.getAndSet(false);
                if (method.getName().equals("close")) return null;
                if (method.getName().equals("wasNull")) return wasNull.get();
                String name = String.valueOf(args[0]);
                if (!row.containsKey(name)) throw new AssertionError("Unexpected column: " + name);
                Object value = row.get(name);
                wasNull.set(value == null);
                return switch (method.getName()) {
                  case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                  case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                  case "getBoolean" -> value != null && (Boolean) value;
                  case "getObject" -> value;
                  default ->
                      throw new AssertionError("Unexpected result operation: " + method.getName());
                };
              });
    }
  }
}
