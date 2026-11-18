package io.github.gbalazsfalvi.slotkeeper.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.*;
import io.github.gbalazsfalvi.slotkeeper.core.*;
import io.github.gbalazsfalvi.slotkeeper.core.identity.Actor;
import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import java.time.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Deterministic PostgreSQL clock-fault fixtures never change the host or container system clock.
 */
@Tag("docker")
class DatabaseTimeTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  "postgres:18.7@sha256:2ea8630df176ad090ec92136dee850397c5af8781e4bcf2e4162593691c61bbc")
              .asCompatibleSubstituteFor("postgres"));
  private static DriverManagerDataSource owner, runtime;
  private JdbcTemplate sql;
  private ReservationService service;
  private final UUID workspace = DemoSeed.id("harbor"), room = DemoSeed.id("harbor-oak");
  private final Actor actor = new Actor(DemoSeed.id("alex"));
  private Instant slot;

  @BeforeAll
  static void database() {
    POSTGRES.start();
    owner =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    new JdbcTemplate(owner)
        .execute("CREATE ROLE slotkeeper_app LOGIN PASSWORD 'local-clock-test-only'");
    Flyway.configure()
        .dataSource(owner)
        .defaultSchema("slotkeeper")
        .schemas("slotkeeper")
        .load()
        .migrate();
    String url = POSTGRES.getJdbcUrl() + "&currentSchema=slotkeeper";
    owner.setUrl(url);
    runtime = new DriverManagerDataSource(url, "slotkeeper_app", "local-clock-test-only");
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void fixture() {
    sql = new JdbcTemplate(owner);
    sql.execute(
        "TRUNCATE booking_events,idempotency_records,actor_booking_guards,reservations,slots,rooms,workspace_memberships,users,workspaces,decision_clock CASCADE");
    sql.update("INSERT INTO decision_clock(id,last_observed) VALUES(1,clock_timestamp())");
    DemoSeed.populate(owner);
    service = new ReservationService(new JdbcBookingUnitOfWork(runtime), BookingPolicy.defaults());
    slot =
        LocalDate.now(ZoneId.of("Europe/Budapest"))
            .plusDays(1)
            .atTime(10, 0)
            .atZone(ZoneId.of("Europe/Budapest"))
            .toInstant();
  }

  @Test
  void aPastObservationAheadOfWallTimeDoesNotBreakHoldConfirmOrCancel() {
    Instant known = advance(Duration.ofSeconds(5));
    ReservationView held = service.createHold(actor, workspace, room, slot, "clock-fault-hold");
    assertFalse(held.createdAt().isBefore(known));
    ReservationView confirmed = service.confirm(actor, workspace, held.id(), "clock-fault-confirm");
    assertEquals(ReservationStatus.CONFIRMED, confirmed.status());
    assertFalse(confirmed.confirmedAt().isBefore(held.createdAt()));
    assertEquals(ReservationStatus.CANCELLED, service.cancel(actor, workspace, held.id()).status());
    assertFalse(new DatabaseTime(runtime).now().isBefore(confirmed.confirmedAt()));
    assertEquals(120, Duration.between(held.createdAt(), held.expiresAt()).toSeconds());
  }

  @Test
  void anObservedExpiredHoldCannotReviveWhenWallTimeTrailsTheFence() {
    ReservationView held = service.createHold(actor, workspace, room, slot, "expiry-fence-hold");
    sql.update(
        "UPDATE decision_clock SET last_observed=? WHERE id=1",
        held.expiresAt().plusSeconds(1).atOffset(ZoneOffset.UTC));
    assertEquals(ReservationStatus.EXPIRED, service.get(actor, workspace, held.id()).status());
    // The stored row is still HELD: this proves durable observation, not only physical cleanup.
    assertEquals(
        "HELD",
        sql.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, held.id()));
    assertEquals(
        ProblemCode.HOLD_EXPIRED,
        assertThrows(
                RuleViolation.class,
                () -> service.confirm(actor, workspace, held.id(), "expired-fence-confirm"))
            .code());
    var availability =
        new JdbcBookingQueries(runtime)
            .availability(
                actor, workspace, room, slot.atZone(ZoneId.of("Europe/Budapest")).toLocalDate());
    assertEquals(
        BookingQueries.AvailabilityStatus.AVAILABLE,
        availability.slots().stream()
            .filter(s -> s.startAt().equals(slot))
            .findFirst()
            .orElseThrow()
            .availability());
  }

  @Test
  void timeObservationSurvivesABusinessRollback() {
    sql.execute("TRUNCATE decision_clock");
    sql.update("INSERT INTO decision_clock VALUES(1,clock_timestamp()-interval '20 seconds')");
    Instant before = stored();
    assertThrows(
        IllegalStateException.class,
        () ->
            new JdbcBookingUnitOfWork(runtime)
                .execute(
                    tx -> {
                      tx.now();
                      throw new IllegalStateException("Injected business rollback.");
                    }));
    assertTrue(stored().isAfter(before));
    assertEquals(0, sql.queryForObject("SELECT count(*) FROM reservations", Integer.class));
  }

  @Test
  void runtimeCannotRegressOrRemoveTheSingleton() {
    JdbcTemplate app = new JdbcTemplate(runtime);
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            app.update(
                "UPDATE decision_clock SET last_observed=last_observed-interval '1 second' WHERE id=1"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> app.update("DELETE FROM decision_clock"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> app.update("INSERT INTO decision_clock VALUES(2,clock_timestamp())"));
  }

  @Test
  void aFullyOccupiedPrimaryPoolDoesNotStarveTheClockConnection() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(runtime.getUrl());
    config.setUsername(runtime.getUsername());
    config.setPassword(runtime.getPassword());
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(1000);
    try (HikariDataSource pool = new HikariDataSource(config)) {
      var pooledService =
          new ReservationService(new JdbcBookingUnitOfWork(pool), BookingPolicy.defaults());
      assertTimeoutPreemptively(
          Duration.ofSeconds(5),
          () ->
              assertEquals(
                  ReservationStatus.HELD,
                  pooledService
                      .createHold(actor, workspace, room, slot, "one-connection-pool")
                      .status()));
    }
  }

  @Test
  void clockPermissionFailureHasNoWallTimeFallback() {
    sql.execute("REVOKE UPDATE(last_observed) ON decision_clock FROM slotkeeper_app");
    try {
      assertEquals(
          ProblemCode.SERVICE_UNAVAILABLE,
          assertThrows(RuleViolation.class, () -> new DatabaseTime(runtime).now()).code());
    } finally {
      sql.execute("GRANT UPDATE(last_observed) ON decision_clock TO slotkeeper_app");
    }
  }

  private Instant advance(Duration offset) {
    Instant future =
        sql.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)
            .toInstant()
            .plus(offset);
    sql.update(
        "UPDATE decision_clock SET last_observed=? WHERE id=1", future.atOffset(ZoneOffset.UTC));
    return future;
  }

  private Instant stored() {
    return sql.queryForObject(
            "SELECT last_observed FROM decision_clock WHERE id=1", OffsetDateTime.class)
        .toInstant();
  }

  @Test
  void upgradingV1BootstrapsCommittedHistoryButExcludesFutureDeadlines() {
    sql.execute("CREATE DATABASE slotkeeper_bootstrap");
    String url =
        POSTGRES
                .getJdbcUrl()
                .replace("/" + POSTGRES.getDatabaseName() + "?", "/slotkeeper_bootstrap?")
            + "&currentSchema=slotkeeper";
    assertTrue(url.contains("/slotkeeper_bootstrap?"));
    DriverManagerDataSource source =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(source)
        .defaultSchema("slotkeeper")
        .schemas("slotkeeper")
        .target("001")
        .load()
        .migrate();
    JdbcTemplate fixture = new JdbcTemplate(source);
    UUID person = UUID.randomUUID(),
        workspaceId = UUID.randomUUID(),
        roomId = UUID.randomUUID(),
        slotId = UUID.randomUUID(),
        reservationId = UUID.randomUUID();
    Instant created =
        fixture
            .queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)
            .toInstant()
            .plusSeconds(10);
    fixture.update(
        "INSERT INTO users(id,username,display_name,password_hash) VALUES(?,'bootstrap','Synthetic bootstrap','unused-fixture-hash')",
        person);
    fixture.update(
        "INSERT INTO workspaces(id,name,time_zone) VALUES(?,'Bootstrap','Europe/Budapest')",
        workspaceId);
    fixture.update(
        "INSERT INTO workspace_memberships(workspace_id,user_id,role) VALUES(?,?,'MEMBER')",
        workspaceId,
        person);
    fixture.update(
        "INSERT INTO rooms(id,workspace_id,name,description) VALUES(?,?,'Fixture','')",
        roomId,
        workspaceId);
    fixture.update(
        "INSERT INTO slots(id,workspace_id,room_id,start_at,end_at) VALUES(?,?,?,?,?)",
        slotId,
        workspaceId,
        roomId,
        slot.atOffset(ZoneOffset.UTC),
        slot.plusSeconds(1800).atOffset(ZoneOffset.UTC));
    fixture.update(
        "INSERT INTO reservations(id,workspace_id,room_id,slot_id,owner_id,start_at,end_at,status,created_at,expires_at,confirmed_at) VALUES(?,?,?,?,?,?,?,'CANCELLED',?,?,?)",
        reservationId,
        workspaceId,
        roomId,
        slotId,
        person,
        slot.atOffset(ZoneOffset.UTC),
        slot.plusSeconds(1800).atOffset(ZoneOffset.UTC),
        created.atOffset(ZoneOffset.UTC),
        created.plusSeconds(120).atOffset(ZoneOffset.UTC),
        created.plusSeconds(1).atOffset(ZoneOffset.UTC));
    int offset = 0;
    for (String kind : new String[] {"HOLD_CREATED", "CONFIRMED", "CANCELLED"})
      fixture.update(
          "INSERT INTO booking_events(id,workspace_id,kind,reservation_id,actor_id,room_id,occurred_at) VALUES(?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          workspaceId,
          kind,
          reservationId,
          person,
          roomId,
          created.plusSeconds(offset++).atOffset(ZoneOffset.UTC));
    Flyway.configure()
        .dataSource(source)
        .defaultSchema("slotkeeper")
        .schemas("slotkeeper")
        .load()
        .migrate();
    Instant observed = new DatabaseTime(source).now();
    assertFalse(observed.isBefore(created.plusSeconds(2)));
    assertTrue(
        observed.isBefore(created.plusSeconds(120)),
        "A hold deadline must not seed decision time.");
    assertTrue(observed.isBefore(slot), "A future slot start must not seed decision time.");
  }

  @Test
  void aBlockedClockRowFailsWithinTheQueryBudgetWithoutReturningAnOldValue() throws Exception {
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var blocker = owner.getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement("SELECT id FROM decision_clock WHERE id=1 FOR UPDATE")) {
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
        }
      }
      var observed =
          pool.submit(
              () ->
                  assertThrows(RuleViolation.class, () -> new DatabaseTime(runtime).now()).code());
      try {
        assertEquals(
            ProblemCode.RESOURCE_BUSY, observed.get(5, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        blocker.rollback();
      }
    }
  }
}
