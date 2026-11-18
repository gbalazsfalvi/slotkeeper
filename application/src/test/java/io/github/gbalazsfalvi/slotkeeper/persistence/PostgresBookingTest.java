package io.github.gbalazsfalvi.slotkeeper.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import io.github.gbalazsfalvi.slotkeeper.core.identity.Actor;
import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import java.time.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL integration checks; no in-memory substitute is used for locking claims. */
@Tag("docker")
class PostgresBookingTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              org.testcontainers.utility.DockerImageName.parse(
                      "postgres:18.7@sha256:2ea8630df176ad090ec92136dee850397c5af8781e4bcf2e4162593691c61bbc")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("slotkeeper_test");
  private static DriverManagerDataSource owner;
  private static DriverManagerDataSource runtime;
  private JdbcTemplate sql;
  private ReservationService service;
  private UUID workspace;
  private UUID room;
  private Actor alex;
  private Instant start;

  @BeforeAll
  static void database() {
    POSTGRES.start();
    owner =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate admin = new JdbcTemplate(owner);
    admin.execute("CREATE ROLE slotkeeper_app LOGIN PASSWORD 'local-test-only'");
    Flyway.configure()
        .dataSource(owner)
        .defaultSchema("slotkeeper")
        .schemas("slotkeeper")
        .load()
        .migrate();
    runtime =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=slotkeeper",
            "slotkeeper_app",
            "local-test-only");
    owner.setUrl(
        POSTGRES.getJdbcUrl()
            + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
            + "currentSchema=slotkeeper");
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void fixture() {
    sql = new JdbcTemplate(owner);
    sql.execute(
        "TRUNCATE booking_events, idempotency_records, actor_booking_guards, reservations, slots, rooms, workspace_memberships, users, workspaces CASCADE");
    DemoSeed.populate(owner);
    workspace = DemoSeed.id("harbor");
    room = DemoSeed.id("harbor-oak");
    alex = new Actor(DemoSeed.id("alex"));
    start =
        LocalDate.now(ZoneId.of("Europe/Budapest"))
            .plusDays(1)
            .atTime(10, 0)
            .atZone(ZoneId.of("Europe/Budapest"))
            .toInstant();
    service = new ReservationService(new JdbcBookingUnitOfWork(runtime), BookingPolicy.defaults());
  }

  @Test
  void durableRetryConflictAndNonRefundableQuota() {
    ReservationView first = service.createHold(alex, workspace, room, start, "first-request");
    ReservationView retry = service.createHold(alex, workspace, room, start, "first-request");
    assertEquals(first.id(), retry.id());
    assertTrue(retry.replayed());
    assertEquals(
        ProblemCode.IDEMPOTENCY_CONFLICT,
        assertThrows(
                RuleViolation.class,
                () ->
                    service.createHold(
                        alex, workspace, room, start.plusSeconds(1800), "first-request"))
            .code());
    service.confirm(alex, workspace, first.id(), "first-confirm");
    service.cancel(alex, workspace, first.id());
    assertEquals(
        ReservationStatus.CANCELLED,
        service.confirm(alex, workspace, first.id(), "first-confirm").status());
    assertEquals(
        1,
        sql.queryForObject(
            "SELECT count(*) FROM reservations WHERE confirmed_at IS NOT NULL", Integer.class));
    assertEquals(3, sql.queryForObject("SELECT count(*) FROM booking_events", Integer.class));
  }

  @Test
  void capacityAndActorAllowanceAreDurable() {
    service.createHold(alex, workspace, room, start, "hold-first-one");
    assertEquals(
        ProblemCode.SLOT_UNAVAILABLE,
        assertThrows(
                RuleViolation.class,
                () ->
                    service.createHold(
                        new Actor(DemoSeed.id("morgan")),
                        workspace,
                        room,
                        start,
                        "other-first-one"))
            .code());
    service.createHold(alex, workspace, room, start.plusSeconds(1800), "hold-second-one");
    assertEquals(
        ProblemCode.QUOTA_EXCEEDED,
        assertThrows(
                RuleViolation.class,
                () ->
                    service.createHold(
                        alex, workspace, room, start.plusSeconds(3600), "hold-third-one"))
            .code());
    assertEquals(2, sql.queryForObject("SELECT count(*) FROM reservations", Integer.class));
  }

  @Test
  void foreignScopeAndOwnerAreNotVisible() {
    ReservationView hold = service.createHold(alex, workspace, room, start, "private-hold-one");
    assertEquals(
        ProblemCode.NOT_FOUND,
        assertThrows(
                RuleViolation.class,
                () -> service.get(new Actor(DemoSeed.id("morgan")), workspace, hold.id()))
            .code());
    assertEquals(
        ProblemCode.NOT_FOUND,
        assertThrows(
                RuleViolation.class,
                () ->
                    service.createHold(
                        alex, DemoSeed.id("studio"), room, start, "foreign-hold-one"))
            .code());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            sql.update(
                "UPDATE slots SET assigned_reservation_id=? WHERE workspace_id=?",
                hold.id(),
                DemoSeed.id("studio")));
  }

  @Test
  void runtimeCannotRewriteEventsOrSchema() {
    JdbcTemplate app = new JdbcTemplate(runtime);
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> app.update("DELETE FROM booking_events"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> app.execute("CREATE TABLE forbidden_table(id integer)"));
  }

  @Test
  void queriesRecheckMembershipRatherThanTrustingAStaleSession() {
    JdbcBookingQueries queries = new JdbcBookingQueries(runtime);
    assertEquals(1, queries.workspaces(alex).size());
    assertEquals(
        16,
        queries
            .availability(
                alex, workspace, room, start.atZone(ZoneId.of("Europe/Budapest")).toLocalDate())
            .slots()
            .size());
    sql.update("UPDATE workspace_memberships SET active=false WHERE user_id=?", alex.userId());
    assertEquals(
        ProblemCode.NOT_FOUND,
        assertThrows(RuleViolation.class, () -> queries.rooms(alex, workspace)).code());
  }

  @RepeatedTest(5)
  void aLockWaitMustNotFreezeTheConfirmationClock() throws Exception {
    ReservationView hold = service.createHold(alex, workspace, room, start, "lock-clock-hold");
    // A privileged test fixture narrows the deadline; production always uses the declared 120s TTL.
    sql.update(
        "UPDATE reservations SET expires_at=greatest(clock_timestamp(),created_at)+interval '2 seconds' WHERE id=?",
        hold.id());
    try (var blocker = owner.getConnection();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement(
              "SELECT id FROM slots WHERE workspace_id=? AND room_id=? AND start_at=? FOR UPDATE")) {
        statement.setObject(1, workspace);
        statement.setObject(2, room);
        statement.setObject(3, start.atOffset(ZoneOffset.UTC));
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
        }
      }
      var result =
          pool.submit(
              () ->
                  assertThrows(
                      RuleViolation.class,
                      () -> service.confirm(alex, workspace, hold.id(), "lock-clock-confirm")));
      // Wait for the authoritative DB clock, not an assumed host/VM clock relationship.
      Instant wallDeadline = Instant.now().plusSeconds(8);
      while (Boolean.TRUE.equals(
          sql.queryForObject(
              "SELECT clock_timestamp()<expires_at FROM reservations WHERE id=?",
              Boolean.class,
              hold.id()))) {
        assertTrue(Instant.now().isBefore(wallDeadline), "Database deadline did not advance.");
        Thread.sleep(40);
      }
      blocker.commit();
      RuleViolation observed = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          ProblemCode.HOLD_EXPIRED,
          observed.code(),
          java.util.Arrays.toString(observed.getStackTrace()));
      assertNull(
          sql.queryForObject(
              "SELECT confirmed_at FROM reservations WHERE id=?", OffsetDateTime.class, hold.id()));
    }
  }

  @Test
  void staleCancellationCannotReleaseANewAssignment() {
    ReservationView old = service.createHold(alex, workspace, room, start, "old-reservation");
    service.cancel(alex, workspace, old.id());
    ReservationView replacement =
        service.createHold(
            new Actor(DemoSeed.id("morgan")), workspace, room, start, "new-reservation");
    service.cancel(alex, workspace, old.id());
    assertEquals(
        replacement.id(),
        sql.queryForObject(
            "SELECT assigned_reservation_id FROM slots WHERE workspace_id=? AND room_id=? AND start_at=?",
            UUID.class,
            workspace,
            room,
            start.atOffset(ZoneOffset.UTC)));
  }

  @Test
  void failedTransactionRollsBackEveryEffectIncludingRetryAndEvent() {
    JdbcBookingUnitOfWork delegate = new JdbcBookingUnitOfWork(runtime);
    BookingUnitOfWork failed =
        new BookingUnitOfWork() {
          @Override
          public <T> T execute(java.util.function.Function<BookingTransaction, T> work) {
            return delegate.execute(
                tx -> {
                  work.apply(tx);
                  throw new IllegalStateException("Injected post-effect rollback.");
                });
          }
        };
    assertThrows(
        IllegalStateException.class,
        () ->
            new ReservationService(failed, BookingPolicy.defaults())
                .createHold(alex, workspace, room, start, "rollback-request"));
    for (String table : new String[] {"reservations", "idempotency_records", "booking_events"})
      assertEquals(0, sql.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    assertEquals(
        0,
        sql.queryForObject(
            "SELECT count(*) FROM slots WHERE assigned_reservation_id IS NOT NULL", Integer.class));
  }

  @Test
  void cancellationDoesNotRefundTheDailyConfirmationAllowance() {
    for (int i = 0; i < 4; i++) {
      ReservationView hold =
          service.createHold(
              alex, workspace, room, start.plusSeconds(i * 1800L), "quota-hold-" + i);
      service.confirm(alex, workspace, hold.id(), "quota-confirm-" + i);
      service.cancel(alex, workspace, hold.id());
    }
    assertEquals(
        ProblemCode.QUOTA_EXCEEDED,
        assertThrows(
                RuleViolation.class,
                () ->
                    service.createHold(
                        alex, workspace, room, start.plusSeconds(7200), "quota-fifth-hold"))
            .code());
  }

  @Test
  void oneHundredAttemptsFromTwoIndependentProcessesHaveExactlyOneWinner(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    for (int i = 0; i < 100; i++) {
      UUID actor = DemoSeed.id("contender-" + i);
      sql.update(
          "INSERT INTO users(id,username,display_name,password_hash) SELECT ?,?,? ,password_hash FROM users WHERE id=?",
          actor,
          "contender-" + i,
          "Contender",
          alex.userId());
      sql.update(
          "INSERT INTO workspace_memberships(workspace_id,user_id,role) VALUES(?,?,'MEMBER')",
          workspace,
          actor);
    }
    String classpath = System.getProperty("slotkeeper.test.classpath").replace('\\', '/');
    java.nio.file.Path arguments = directory.resolve("java.args");
    java.nio.file.Files.writeString(
        arguments,
        "-Xmx128m\n-classpath\n\"" + classpath + "\"\n" + ContentionWorker.class.getName() + "\n");
    java.util.List<Process> processes = new java.util.ArrayList<>();
    Instant began = Instant.now();
    try {
      for (int worker = 0; worker < 2; worker++) {
        ProcessBuilder process =
            new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + arguments,
                Integer.toString(worker),
                directory.toString());
        process.environment().put("SK_TEST_DB_URL", runtime.getUrl());
        process.environment().put("SK_TEST_DB_USER", runtime.getUsername());
        process.environment().put("SK_TEST_DB_PASSWORD", runtime.getPassword());
        process.environment().put("SK_TEST_SLOT", start.toString());
        processes.add(
            process
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("worker-" + worker + ".log").toFile())
                .start());
      }
      Instant deadline = Instant.now().plusSeconds(35);
      while (!java.nio.file.Files.exists(directory.resolve("ready-0"))
          || !java.nio.file.Files.exists(directory.resolve("ready-1"))) {
        assertTrue(
            Instant.now().isBefore(deadline), "Independent workers did not reach the barrier.");
        for (Process process : processes)
          assertTrue(process.isAlive(), "Worker exited before the start barrier.");
        Thread.sleep(25);
      }
      java.nio.file.Files.writeString(directory.resolve("start"), "start");
      for (Process process : processes) {
        assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(
            0, process.exitValue(), "Independent worker failed; inspect private test log.");
      }
      java.util.Map<String, Long> outcomes = new java.util.TreeMap<>();
      for (int worker = 0; worker < 2; worker++)
        for (String outcome :
            java.nio.file.Files.readAllLines(directory.resolve("outcomes-" + worker)))
          outcomes.merge(outcome, 1L, Long::sum);
      int active =
          sql.queryForObject(
              "SELECT count(*) FROM slots WHERE assigned_reservation_id IS NOT NULL",
              Integer.class);
      int reservations = sql.queryForObject("SELECT count(*) FROM reservations", Integer.class);
      assertEquals(java.util.Map.of("CREATED", 1L, "SLOT_UNAVAILABLE", 99L), outcomes);
      assertEquals(1, active);
      assertEquals(1, reservations);
      var evidence = new java.util.LinkedHashMap<String, Object>();
      evidence.put("kind", "DATABASE_CONTENTION");
      evidence.put("attempts", 100);
      evidence.put("workers", 2);
      evidence.put("processIds", processes.stream().map(Process::pid).toList());
      evidence.put("outcomes", outcomes);
      evidence.put("activeAssignments", active);
      evidence.put("distinctReservations", reservations);
      evidence.put("startedAt", began.toString());
      evidence.put("finishedAt", Instant.now().toString());
      evidence.put("revision", System.getenv().getOrDefault("SLOTKEEPER_REVISION", "working-tree"));
      evidence.put("result", "PASS");
      java.nio.file.Path output =
          java.nio.file.Path.of(
              System.getenv()
                  .getOrDefault(
                      "SLOTKEEPER_CONTENTION_OUTPUT", "build/evidence/database-contention.json"));
      java.nio.file.Files.createDirectories(output.toAbsolutePath().getParent());
      java.nio.file.Files.writeString(
          output, new tools.jackson.databind.json.JsonMapper().writeValueAsString(evidence));
    } finally {
      for (Process process : processes) if (process.isAlive()) process.destroyForcibly();
    }
  }

  @Test
  void concurrentRetriesCommitOneResourceAndOneEvent() throws Exception {
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var barrier = new java.util.concurrent.CyclicBarrier(2);
      java.util.concurrent.Callable<ReservationView> request =
          () -> {
            barrier.await();
            return service.createHold(alex, workspace, room, start, "shared-retry-key");
          };
      var first = pool.submit(request);
      var second = pool.submit(request);
      ReservationView a = first.get(15, java.util.concurrent.TimeUnit.SECONDS),
          b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(a.id(), b.id());
      assertNotEquals(a.replayed(), b.replayed());
      assertEquals(1, sql.queryForObject("SELECT count(*) FROM reservations", Integer.class));
      assertEquals(1, sql.queryForObject("SELECT count(*) FROM booking_events", Integer.class));
      assertEquals(
          1, sql.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class));
    }
  }

  @Test
  void concurrentExpiryWorkersApplyOneEventAndReleaseOnlyTheMatchingAssignment() throws Exception {
    ReservationView hold = service.createHold(alex, workspace, room, start, "expiry-worker-key");
    sql.update(
        "UPDATE reservations SET created_at=clock_timestamp()-interval '10 seconds',expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
        hold.id());
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> new ExpiryWorker(runtime).expireBatch());
      var second = pool.submit(() -> new ExpiryWorker(runtime).expireBatch());
      assertEquals(
          1,
          first.get(10, java.util.concurrent.TimeUnit.SECONDS)
              + second.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertEquals(
        "EXPIRED",
        sql.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, hold.id()));
    assertEquals(
        1,
        sql.queryForObject(
            "SELECT count(*) FROM booking_events WHERE kind='EXPIRED'", Integer.class));
    ReservationView replacement =
        service.createHold(
            new Actor(DemoSeed.id("morgan")), workspace, room, start, "expiry-replacement-key");
    assertEquals(0, new ExpiryWorker(runtime).expireBatch());
    assertEquals(
        replacement.id(),
        sql.queryForObject(
            "SELECT assigned_reservation_id FROM slots WHERE workspace_id=? AND room_id=? AND start_at=?",
            UUID.class,
            workspace,
            room,
            start.atOffset(ZoneOffset.UTC)));
  }

  @Test
  void expirySkipsABusyRoomBeforeAcquiringAnySlotLock() throws Exception {
    ReservationView held = service.createHold(alex, workspace, room, start, "busy-room-expiry");
    sql.update(
        "UPDATE reservations SET created_at=clock_timestamp()-interval '10 seconds',expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
        held.id());
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var blocker = owner.getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement(
              "SELECT id FROM rooms WHERE workspace_id=? AND id=? FOR UPDATE")) {
        statement.setObject(1, workspace);
        statement.setObject(2, room);
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
        }
      }
      var result = pool.submit(() -> new ExpiryWorker(runtime).expireBatch());
      try {
        assertEquals(0, result.get(2, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        blocker.rollback();
      }
    }
    assertEquals(
        "HELD",
        sql.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, held.id()));
    assertEquals(1, new ExpiryWorker(runtime).expireBatch());
  }
}
