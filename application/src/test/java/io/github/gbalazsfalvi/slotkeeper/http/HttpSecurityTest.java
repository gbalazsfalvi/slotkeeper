package io.github.gbalazsfalvi.slotkeeper.http;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gbalazsfalvi.slotkeeper.SlotkeeperApplication;
import io.github.gbalazsfalvi.slotkeeper.persistence.DemoSeed;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual HTTP, PostgreSQL and shared-session checks; assertions do not mock the security chain. */
@Tag("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpSecurityTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  "postgres:18.7@sha256:2ea8630df176ad090ec92136dee850397c5af8781e4bcf2e4162593691c61bbc")
              .asCompatibleSubstituteFor("postgres"));
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static DriverManagerDataSource owner;
  private static JdbcTemplate sql;
  private static ConfigurableApplicationContext first, second;
  private static String origin, otherOrigin;
  private static final UUID HARBOR = DemoSeed.id("harbor"),
      STUDIO = DemoSeed.id("studio"),
      OAK = DemoSeed.id("harbor-oak");
  private Browser browser;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    owner =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    new JdbcTemplate(owner)
        .execute("CREATE ROLE slotkeeper_app LOGIN PASSWORD 'local-http-test-only'");
    Flyway.configure()
        .dataSource(owner)
        .defaultSchema("slotkeeper")
        .schemas("slotkeeper")
        .load()
        .migrate();
    String jdbc = POSTGRES.getJdbcUrl() + "&currentSchema=slotkeeper";
    owner.setUrl(jdbc);
    sql = new JdbcTemplate(owner);
    DemoSeed.populate(owner);
    first = context(jdbc);
    second = context(jdbc);
    origin = "http://127.0.0.1:" + ((WebServerApplicationContext) first).getWebServer().getPort();
    otherOrigin =
        "http://127.0.0.1:" + ((WebServerApplicationContext) second).getWebServer().getPort();
  }

  private static ConfigurableApplicationContext context(String jdbc) {
    return new SpringApplicationBuilder(SlotkeeperApplication.class)
        .run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--spring.datasource.url=" + jdbc,
            "--spring.datasource.username=slotkeeper_app",
            "--spring.datasource.password=local-http-test-only",
            "--slotkeeper.maintenance-enabled=false");
  }

  @AfterAll
  static void stop() {
    if (first != null) first.close();
    if (second != null) second.close();
    POSTGRES.stop();
  }

  @BeforeEach
  void reset() {
    sql.execute(
        "TRUNCATE booking_events,idempotency_records,reservations,slots,rate_buckets,spring_session CASCADE");
    DemoSeed.provisionSlots(sql);
    browser = new Browser();
  }

  @Test
  void anonymousApiUses401AndLoginRotatesTheSession() throws Exception {
    assertEquals(401, browser.get(origin, "/api/workspaces").statusCode());
    browser.csrf(origin);
    String anonymousCookie = browser.sessionCookie();
    browser.login(origin, "alex");
    assertNotEquals(anonymousCookie, browser.sessionCookie());
    JsonNode view = JSON.readTree(browser.get(origin, "/api/session").body());
    assertTrue(view.path("authenticated").asBoolean());
    assertFalse(view.toString().contains("password"));
    HttpResponse<String> protectedView = browser.get(origin, "/api/workspaces");
    assertEquals(200, protectedView.statusCode());
    assertEquals(1, JSON.readTree(protectedView.body()).path("items").size());
    String cookie = browser.get(origin, "/api/csrf").headers().allValues("set-cookie").toString();
    assertFalse(protectedView.headers().firstValue("content-security-policy").orElse("").isBlank());
  }

  @Test
  void missingCsrfAndForeignWorkspaceCannotMutate() throws Exception {
    browser.login(origin, "alex");
    assertEquals(
        403,
        browser
            .post(origin, "/api/workspaces/" + HARBOR + "/holds", holdBody(0), null, false)
            .statusCode());
    assertEquals(404, browser.get(origin, "/api/workspaces/" + STUDIO + "/rooms").statusCode());
    assertEquals(0, sql.queryForObject("SELECT count(*) FROM reservations", Integer.class));
  }

  @Test
  void scopedRoleNeverBecomesAGlobalAdmin() throws Exception {
    browser.login(origin, "jordan");
    assertEquals(
        403,
        browser
            .post(
                origin,
                "/api/workspaces/" + HARBOR + "/rooms",
                "{\"name\":\"Denied\",\"description\":\"\"}",
                null,
                true)
            .statusCode());
    assertEquals(
        201,
        browser
            .post(
                origin,
                "/api/workspaces/" + STUDIO + "/rooms",
                "{\"name\":\"Allowed\",\"description\":\"\"}",
                null,
                true)
            .statusCode());
  }

  @Test
  void unknownDuplicateOversizedAndWrongContentTypeInputsAreRejected() throws Exception {
    browser.login(origin, "alex");
    String path = "/api/workspaces/" + HARBOR + "/holds";
    assertEquals(
        400,
        browser
            .post(
                origin,
                path,
                holdBody(0).replace("}", ",\"ownerId\":\"" + DemoSeed.id("morgan") + "\"}"),
                "forged-owner-key",
                true)
            .statusCode());
    assertEquals(
        400,
        browser
            .post(
                origin,
                path,
                holdBody(0).replace("}", ",\"roomId\":\"" + OAK + "\"}"),
                "duplicate-key",
                true)
            .statusCode());
    assertEquals(
        413,
        browser
            .post(
                origin, path, "{\"padding\":\"" + "x".repeat(17000) + "\"}", "oversized-key", true)
            .statusCode());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(origin + path))
            .header("Content-Type", "text/plain")
            .header("X-CSRF-TOKEN", browser.token)
            .POST(HttpRequest.BodyPublishers.ofString(holdBody(0)))
            .build();
    assertEquals(
        415, browser.client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(
        400,
        browser.get(origin, "/api/workspaces/" + HARBOR + "/my-bookings?size=999999").statusCode());
  }

  @Test
  void anotherOwnerCannotCancelAndRevocationDefeatsAReceiptReplay() throws Exception {
    browser.login(origin, "alex");
    String path = "/api/workspaces/" + HARBOR + "/holds";
    HttpResponse<String> created =
        browser.post(origin, path, holdBody(0), "protected-hold-key", true);
    assertEquals(201, created.statusCode(), created.body());
    String id = JSON.readTree(created.body()).path("id").asString();
    Browser other = new Browser();
    other.login(origin, "morgan");
    assertEquals(
        404,
        other
            .post(
                origin,
                "/api/workspaces/" + HARBOR + "/reservations/" + id + "/cancel",
                "{}",
                null,
                true)
            .statusCode());
    sql.update(
        "UPDATE workspace_memberships SET active=false WHERE workspace_id=? AND user_id=?",
        HARBOR,
        DemoSeed.id("alex"));
    try {
      assertEquals(
          404, browser.post(origin, path, holdBody(0), "protected-hold-key", true).statusCode());
    } finally {
      sql.update(
          "UPDATE workspace_memberships SET active=true WHERE workspace_id=? AND user_id=?",
          HARBOR,
          DemoSeed.id("alex"));
    }
  }

  @Test
  void sessionsAndLogoutAreSharedAcrossTwoActualAppInstances() throws Exception {
    browser.login(origin, "alex");
    assertEquals(200, browser.get(otherOrigin, "/api/workspaces").statusCode());
    assertEquals(204, browser.post(otherOrigin, "/api/logout", "{}", null, true).statusCode());
    assertEquals(401, browser.get(origin, "/api/workspaces").statusCode());
  }

  @Test
  void atomicLimitsIgnoreForgedForwardingHeaders() throws Exception {
    browser.login(origin, "alex");
    int rejected = 0;
    for (int i = 0; i < 125; i++) {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      (i % 2 == 0 ? origin : otherOrigin) + "/api/workspaces/" + HARBOR + "/holds"))
              .header("Content-Type", "application/json")
              .header("X-CSRF-TOKEN", browser.token)
              .header("Idempotency-Key", "limit-key-" + i)
              .header("X-Forwarded-For", "192.0.2." + (i % 250 + 1))
              .POST(HttpRequest.BodyPublishers.ofString(holdBody(0)))
              .build();
      HttpResponse<String> response =
          browser.client.send(request, HttpResponse.BodyHandlers.ofString());
      assertTrue(Set.of(201, 409, 429).contains(response.statusCode()), response.body());
      if (response.statusCode() == 429) {
        rejected++;
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElse("0")) >= 1);
      }
    }
    assertTrue(rejected > 0);
    assertEquals(1, sql.queryForObject("SELECT count(*) FROM reservations", Integer.class));
  }

  @Test
  void foreignWorkspaceProbesDoNotAllocateMutationBuckets() throws Exception {
    browser.login(origin, "alex");
    assertEquals(
        404,
        browser
            .post(
                origin,
                "/api/workspaces/" + STUDIO + "/holds",
                holdBody(0),
                "foreign-bucket-key",
                true)
            .statusCode());
    assertEquals(
        0,
        sql.queryForObject(
            "SELECT count(*) FROM rate_buckets WHERE kind='MUTATION'", Integer.class));
  }

  @Test
  void loginAndAnonymousTokenLimitsUseActualPeerAcrossBothInstances() throws Exception {
    browser.csrf(origin);
    int rejected = 0;
    for (int i = 0; i < 45; i++) {
      String target = i % 2 == 0 ? origin : otherOrigin;
      var request =
          HttpRequest.newBuilder(URI.create(target + "/api/session"))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("X-CSRF-TOKEN", browser.token)
              .header("Forwarded", "for=192.0.2." + (i + 1))
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "username=alex&password=incorrect-local-fixture"))
              .build();
      var response = browser.client.send(request, HttpResponse.BodyHandlers.ofString());
      assertTrue(Set.of(401, 429).contains(response.statusCode()), response.body());
      if (response.statusCode() == 429) rejected++;
    }
    assertTrue(rejected > 0);
    int anonymousRejected = 0;
    // An independent cookie jar is not necessary to trigger the peer admission boundary.
    for (int i = 0; i < 125; i++) {
      var response = browser.get(i % 2 == 0 ? origin : otherOrigin, "/api/csrf");
      assertTrue(Set.of(200, 429).contains(response.statusCode()));
      if (response.statusCode() == 429) anonymousRejected++;
    }
    assertTrue(anonymousRejected > 0);
  }

  @Test
  void adminClosureKeepsExistingBookingsButRejectsNewHolds() throws Exception {
    browser.login(origin, "morgan");
    String path = "/api/workspaces/" + HARBOR;
    var created =
        browser.post(
            origin,
            path + "/rooms",
            "{\"name\":\"HTTP closure check\",\"description\":\"Plain text\"}",
            null,
            true);
    assertEquals(201, created.statusCode());
    String roomId = JSON.readTree(created.body()).path("id").asString();
    var held =
        browser.post(
            origin,
            path + "/holds",
            holdBody(0).replace(OAK.toString(), roomId),
            "closed-room-hold",
            true);
    assertEquals(201, held.statusCode());
    String id = JSON.readTree(held.body()).path("id").asString();
    var patch =
        HttpRequest.newBuilder(URI.create(origin + path + "/rooms/" + roomId))
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", browser.token)
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"enabled\":false}"))
            .build();
    assertEquals(
        200, browser.client.send(patch, HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(
        200,
        browser
            .post(
                origin,
                path + "/reservations/" + id + "/confirm",
                "{}",
                "closed-room-confirm",
                true)
            .statusCode());
    assertEquals(
        409,
        browser
            .post(
                origin,
                path + "/holds",
                holdBody(1).replace(OAK.toString(), roomId),
                "closed-room-new-hold",
                true)
            .statusCode());
    assertTrue(browser.get(origin, path + "/activity").body().contains("CONFIRMED"));
  }

  @Test
  @Order(Integer.MAX_VALUE)
  void anUnavailableDatabaseProducesABounded503InsteadOfLeakingInternals() throws Exception {
    browser.login(origin, "alex");
    POSTGRES.stop();
    var response = browser.get(origin, "/api/workspaces");
    assertEquals(503, response.statusCode(), response.body());
    assertFalse(response.body().contains("jdbc:"));
    assertFalse(response.body().contains("SELECT"));
    assertFalse(response.body().contains("password"));
  }

  private static String holdBody(int offset) {
    return "{\"roomId\":\""
        + OAK
        + "\",\"startAt\":\""
        + LocalDate.now(ZoneId.of("Europe/Budapest"))
            .plusDays(1)
            .atTime(10, 0)
            .plusMinutes(offset * 30L)
            .atZone(ZoneId.of("Europe/Budapest"))
            .toInstant()
        + "\"}";
  }

  private static final class Browser {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    String token;

    HttpResponse<String> get(String target, String path) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create(target + path))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    void csrf(String target) throws Exception {
      token = JSON.readTree(get(target, "/api/csrf").body()).path("token").asString();
      assertFalse(token.isBlank());
    }

    void login(String target, String username) throws Exception {
      csrf(target);
      var request =
          HttpRequest.newBuilder(URI.create(target + "/api/session"))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("X-CSRF-TOKEN", token)
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "username="
                          + username
                          + "&password="
                          + URLEncoder.encode("Slotkeeper-Local-Only!", StandardCharsets.UTF_8)))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      csrf(target);
    }

    String sessionCookie() {
      return cookies.getCookieStore().getCookies().stream()
          .filter(c -> c.getName().equals("SLOTKEEPER_SESSION"))
          .map(HttpCookie::getValue)
          .findFirst()
          .orElse("");
    }

    HttpResponse<String> post(String target, String path, String body, String key, boolean csrf)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(target + path))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/json");
      if (csrf) request.header("X-CSRF-TOKEN", token);
      if (key != null) request.header("Idempotency-Key", key);
      return client.send(
          request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
