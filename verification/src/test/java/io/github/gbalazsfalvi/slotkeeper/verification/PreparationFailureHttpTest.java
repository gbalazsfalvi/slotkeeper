package io.github.gbalazsfalvi.slotkeeper.verification;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PreparationFailureHttpTest {
  @TempDir Path directory;

  @Test
  void malformedOwnedServerTimeStillExportsAllChecksWithoutAnyPass() throws Exception {
    assertPreparationFailure("private-malformed-server-time", "Europe/Budapest");
  }

  @Test
  void malformedOwnedWorkspaceZoneStillExportsAllChecksWithoutAnyPass() throws Exception {
    assertPreparationFailure("2026-11-18T12:00:00Z", "private-invalid-workspace-zone");
  }

  @Test
  void skipsDisabledFirstRoomBeforeCheckingEnabledRoomAvailability() throws Exception {
    try (var fixture =
        new OwnedHttpFixture(
            "2026-11-18T12:00:00Z", "Europe/Budapest", RoomLayout.DISABLED_FIRST)) {
      assertEquals(
          3,
          RehearsalMain.execute(
              new RehearsalOptions(fixture.target(), null, null, directory, "abcdef0", null)));
      assertEquals(1, fixture.availabilityRequests.get());
      assertTrue(fixture.availabilityQuery.contains("roomId=40000000-0000-0000-0000-000000000002"));
      assertEquals(0, fixture.bookingRequests.get());
    }
  }

  @Test
  void allDisabledRoomsLeavePreparationIncompleteWithoutAvailabilityOrBookingAttempts()
      throws Exception {
    try (var fixture =
        new OwnedHttpFixture("2026-11-18T12:00:00Z", "Europe/Budapest", RoomLayout.ALL_DISABLED)) {
      assertEquals(
          3,
          RehearsalMain.execute(
              new RehearsalOptions(fixture.target(), null, null, directory, "abcdef0", null)));
      assertEquals(0, fixture.availabilityRequests.get());
      assertEquals(0, fixture.bookingRequests.get());
      var report =
          JsonMapper.builder().build().readTree(Files.readString(directory.resolve("report.json")));
      assertEquals(15, report.path("counts").path("INCOMPLETE").asInt());
      assertEquals(0, report.path("counts").path("PASS").asInt());
    }
  }

  private enum RoomLayout {
    ENABLED_ONLY,
    DISABLED_FIRST,
    ALL_DISABLED
  }

  private void assertPreparationFailure(String serverNow, String timeZone) throws Exception {
    try (var fixture = new OwnedHttpFixture(serverNow, timeZone)) {
      var options = new RehearsalOptions(fixture.target(), null, null, directory, "abcdef0", null);
      int exitCode = assertDoesNotThrow(() -> RehearsalMain.execute(options));
      assertEquals(2, exitCode);
      for (String name : List.of("report.json", "report.md", "report.html", "SHA256SUMS"))
        assertTrue(Files.isRegularFile(directory.resolve(name)), name);
      String json = Files.readString(directory.resolve("report.json"));
      var report = JsonMapper.builder().build().readTree(json);
      assertFalse(report.path("complete").asBoolean());
      assertEquals(15, report.path("results").size());
      assertEquals(0, report.path("counts").path("PASS").asInt());
      assertEquals(1, report.path("counts").path("ERROR").asInt());
      assertEquals(14, report.path("counts").path("INCOMPLETE").asInt());
      assertEquals("A01", report.path("results").get(0).path("id").asString());
      assertEquals("ERROR", report.path("results").get(0).path("outcome").asString());
      assertEquals("UNEXPECTED_RESPONSE", report.path("results").get(0).path("reason").asString());
      assertTrue(report.path("targets").get(0).path("markerMatched").asBoolean());
      assertEquals(fixture.port(), report.path("targets").get(0).path("port").asInt());
      assertEquals(0, fixture.bookingRequests.get());
      for (String name : List.of("report.json", "report.md", "report.html")) {
        String exported = Files.readString(directory.resolve(name));
        assertFalse(exported.contains("private-malformed-server-time"));
        assertFalse(exported.contains("private-invalid-workspace-zone"));
        assertFalse(exported.contains("test-only-token"));
      }
    }
  }

  /** Owns one ephemeral real HTTP listener and no product state. */
  private static final class OwnedHttpFixture implements AutoCloseable {
    private static final String HARBOR = "10000000-0000-0000-0000-000000000001";
    private static final String STUDIO = "10000000-0000-0000-0000-000000000002";
    private final UUID instanceId = UUID.randomUUID();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicInteger bookingRequests = new AtomicInteger();
    private final AtomicInteger availabilityRequests = new AtomicInteger();
    private String availabilityQuery;

    OwnedHttpFixture(String serverNow, String timeZone) throws IOException {
      this(serverNow, timeZone, RoomLayout.ENABLED_ONLY);
    }

    OwnedHttpFixture(String serverNow, String timeZone, RoomLayout roomLayout) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              String path = exchange.getRequestURI().getPath();
              String actor = actor(exchange);
              if (path.equals("/api/instance")) {
                reply(
                    exchange,
                    200,
                    Map.of(
                        "project",
                        "Slotkeeper",
                        "instanceId",
                        instanceId.toString(),
                        "mode",
                        "LOCAL",
                        "version",
                        "0.1.0"));
              } else if (path.equals("/api/csrf")) {
                reply(
                    exchange,
                    200,
                    Map.of("headerName", "X-CSRF-TOKEN", "token", "test-only-token"));
              } else if (path.equals("/api/session")
                  && exchange.getRequestMethod().equals("POST")) {
                String body =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String username =
                    URLDecoder.decode(
                        body.split("&", 2)[0].substring("username=".length()),
                        StandardCharsets.UTF_8);
                exchange
                    .getResponseHeaders()
                    .add(
                        "Set-Cookie",
                        "FIXTURE_ACTOR=" + username + "; Path=/; HttpOnly; SameSite=Lax");
                reply(
                    exchange,
                    200,
                    Map.of(
                        "authenticated",
                        true,
                        "user",
                        user(username),
                        "serverNow",
                        "2026-11-18T12:00:00Z"));
              } else if (path.equals("/api/session")) {
                reply(
                    exchange,
                    200,
                    Map.of("authenticated", true, "user", user(actor), "serverNow", serverNow));
              } else if (path.equals("/api/workspaces")) {
                String workspace = actor.equals("riley") ? STUDIO : HARBOR;
                reply(
                    exchange,
                    200,
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "id",
                                workspace,
                                "name",
                                "Synthetic workspace",
                                "timeZone",
                                timeZone,
                                "role",
                                "MEMBER"))));
              } else if (path.startsWith("/api/workspaces/") && path.endsWith("/rooms")) {
                var firstRoom =
                    Map.<String, Object>of(
                        "id",
                        "40000000-0000-0000-0000-000000000001",
                        "workspaceId",
                        path.split("/")[3],
                        "name",
                        "Synthetic first room",
                        "description",
                        "Preparation fixture",
                        "enabled",
                        roomLayout == RoomLayout.ENABLED_ONLY);
                var secondRoom =
                    Map.<String, Object>of(
                        "id",
                        "40000000-0000-0000-0000-000000000002",
                        "workspaceId",
                        path.split("/")[3],
                        "name",
                        "Synthetic enabled room",
                        "description",
                        "Preparation fixture",
                        "enabled",
                        true);
                reply(
                    exchange,
                    200,
                    Map.of(
                        "items",
                        roomLayout == RoomLayout.DISABLED_FIRST
                            ? List.of(firstRoom, secondRoom)
                            : List.of(firstRoom)));
              } else if (path.startsWith("/api/workspaces/") && path.endsWith("/availability")) {
                availabilityRequests.incrementAndGet();
                availabilityQuery = exchange.getRequestURI().getRawQuery();
                reply(exchange, 503, Map.of("code", "SERVICE_UNAVAILABLE"));
              } else {
                bookingRequests.incrementAndGet();
                reply(exchange, 503, Map.of("code", "SERVICE_UNAVAILABLE"));
              }
            }
          });
      server.start();
    }

    int port() {
      return server.getAddress().getPort();
    }

    OwnedTarget target() {
      return new OwnedTarget(
          URI.create("http://127.0.0.1:" + port()), instanceId, OwnedTarget.Kind.PRODUCT, port());
    }

    private static String actor(HttpExchange exchange) {
      String cookies = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"));
      return List.of("alex", "morgan", "riley", "jordan").stream()
          .filter(cookies::contains)
          .findFirst()
          .orElse("alex");
    }

    private static Map<String, Object> user(String actor) {
      int ordinal = List.of("alex", "morgan", "riley", "jordan").indexOf(actor) + 1;
      return Map.of(
          "id",
          "20000000-0000-0000-0000-00000000000" + ordinal,
          "displayName",
          "Synthetic fixture actor");
    }

    private void reply(HttpExchange exchange, int status, Map<String, Object> body)
        throws IOException {
      byte[] bytes = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
      server.stop(0);
      executor.close();
    }
  }
}
