package io.github.gbalazsfalvi.slotkeeper.training;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrainingFixtureTest {
  @Test
  void fixedHarborActorLeaksForeignStudioObjectAsMissingScopePositiveControl() {
    var fixture = new TrainingFixture(UUID.fromString("750b9921-28af-4a08-a172-28cdf13f3ad9"));
    var response =
        fixture.reply(
            "GET",
            "/api/workspaces/10000000-0000-0000-0000-000000000001/reservations/30000000-0000-0000-0000-000000000002",
            "harbor-member");
    assertEquals(200, response.status());
    assertEquals("10000000-0000-0000-0000-000000000002", response.body().get("workspaceId"));
    assertEquals("20000000-0000-0000-0000-000000000002", response.body().get("ownerId"));
    assertEquals("CONFIRMED", response.body().get("status"));
  }

  @Test
  void requiresExplicitReducedFixtureActorAndExposesDistinctMarkerOnly() {
    var fixture = new TrainingFixture(UUID.fromString("750b9921-28af-4a08-a172-28cdf13f3ad9"));
    assertEquals(
        403,
        fixture
            .reply(
                "GET",
                "/api/workspaces/10000000-0000-0000-0000-000000000001/reservations/30000000-0000-0000-0000-000000000002",
                null)
            .status());
    assertEquals(404, fixture.reply("POST", "/api/session", "alex").status());
    assertEquals(
        Map.of(
            "project",
            "Slotkeeper Teaching Target",
            "mode",
            "TRAINING",
            "version",
            "0.1.0",
            "instanceId",
            "750b9921-28af-4a08-a172-28cdf13f3ad9"),
        fixture.reply("GET", "/api/instance", null).body());
    assertEquals(404, fixture.reply("GET", "/api/session", "harbor-member").status());
  }
}
