package io.github.gbalazsfalvi.slotkeeper.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceSafetyTest {
  private static final UUID INSTANCE = UUID.fromString("df5c1ce3-36be-4736-ac91-fd1c9a139949");

  @TempDir Path directory;

  @Test
  void refusesNonLoopbackOriginsAndUnexpectedInstanceBeforeAdmittingTarget() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OwnedTarget(
                URI.create("http://example.com:4190"), INSTANCE, OwnedTarget.Kind.PRODUCT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OwnedTarget(
                URI.create("http://127.0.0.1:4190@evil.example"),
                INSTANCE,
                OwnedTarget.Kind.PRODUCT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OwnedTarget(
                URI.create("http://127.0.0.1:4190/other"), INSTANCE, OwnedTarget.Kind.PRODUCT));
    var target =
        new OwnedTarget(URI.create("http://127.0.0.1:4190"), INSTANCE, OwnedTarget.Kind.PRODUCT);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            target.verifyMarker(
                Map.of(
                    "project",
                    "Slotkeeper",
                    "instanceId",
                    UUID.randomUUID().toString(),
                    "mode",
                    "LOCAL",
                    "version",
                    "0.1.0")));
    assertDoesNotThrow(
        () ->
            target.verifyMarker(
                Map.of(
                    "project",
                    "Slotkeeper",
                    "instanceId",
                    INSTANCE.toString(),
                    "mode",
                    "LOCAL",
                    "version",
                    "0.1.0")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            target.verifyMarker(
                Map.of(
                    "project",
                    "Slotkeeper Teaching Target",
                    "instanceId",
                    INSTANCE.toString(),
                    "mode",
                    "TRAINING",
                    "version",
                    "0.1.0")));
  }

  @Test
  void freezesAllowlistedResponseFieldsAndExcludesRuntimeSecrets() throws Exception {
    var observation =
        SanitizedObservation.fromResponse(
            "GET",
            SanitizedObservation.Endpoint.RESERVATION,
            404,
            Map.of(
                "code",
                "NOT_FOUND",
                "requestId",
                INSTANCE.toString(),
                "cookie",
                "cookie-secret",
                "csrf",
                "csrf-secret",
                "password",
                "password-secret",
                "detail",
                "body-secret"),
            12);
    var result =
        new ScenarioResult(
            CheckId.A02,
            Outcome.PASS,
            Reason.OBSERVED,
            List.of(observation),
            List.of(new StateAssertion(AssertionKind.FOREIGN_OBJECT_ABSENT, true)),
            Map.of());
    var report =
        EvidenceReport.of(
            Instant.parse("2026-11-18T12:00:00Z"),
            Instant.parse("2026-11-18T12:01:00Z"),
            "abcdef0",
            List.of(result));
    new EvidenceExporter().write(report, directory);
    String json = Files.readString(directory.resolve("report.json"));
    assertTrue(json.contains("NOT_FOUND"));
    for (String name : List.of("report.json", "report.md", "report.html")) {
      String rendered = Files.readString(directory.resolve(name));
      assertTrue(rendered.contains("NOT_FOUND"));
      assertTrue(rendered.contains(INSTANCE.toString()));
      for (String secret :
          List.of("cookie-secret", "csrf-secret", "password-secret", "body-secret"))
        assertFalse(rendered.contains(secret));
    }
    for (String secret : List.of("cookie-secret", "csrf-secret", "password-secret", "body-secret"))
      assertFalse(json.contains(secret));
    assertThrows(UnsupportedOperationException.class, () -> result.observations().add(observation));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SanitizedObservation.fromResponse(
                "GET",
                SanitizedObservation.Endpoint.RESERVATION,
                404,
                Map.of("code", "cookie-secret"),
                1));
  }

  @Test
  void missingChecksNeverProduceCompleteReportOrPassWithoutAssertions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScenarioResult(
                CheckId.A01, Outcome.PASS, Reason.OBSERVED, List.of(), List.of(), Map.of()));
    var result = ScenarioResult.incomplete(CheckId.A12, Reason.DATABASE_EVIDENCE_MISSING);
    var report = EvidenceReport.of(Instant.EPOCH, Instant.EPOCH, "abcdef0", List.of(result));
    assertFalse(report.complete());
    assertEquals(15, report.results().size());
    assertEquals(15L, report.counts().get(Outcome.INCOMPLETE));
  }

  @Test
  void duplicateCaseInventoryCannotMasqueradeAsEveryMandatoryCheck() {
    var result =
        new ScenarioResult(
            CheckId.A01,
            Outcome.PASS,
            Reason.OBSERVED,
            List.of(),
            List.of(new StateAssertion(AssertionKind.ANONYMOUS_DENIED, true)),
            Map.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EvidenceReport(
                Instant.EPOCH,
                Instant.EPOCH,
                "abcdef0",
                java.util.Collections.nCopies(15, result)));
  }

  @Test
  void retainsTypedSyntheticObjectScopeAndObservationTimeWithoutRawBody() {
    var observed =
        SanitizedObservation.fromResponse(
            "GET",
            SanitizedObservation.Endpoint.RESERVATION,
            200,
            Map.of(
                "id",
                INSTANCE.toString(),
                "workspaceId",
                "10000000-0000-0000-0000-000000000002",
                "ownerId",
                "20000000-0000-0000-0000-000000000002",
                "status",
                "CONFIRMED"),
            3);
    assertEquals(UUID.fromString("10000000-0000-0000-0000-000000000002"), observed.workspaceId());
    assertEquals(UUID.fromString("20000000-0000-0000-0000-000000000002"), observed.ownerId());
    assertNotNull(observed.observedAt());
  }

  @Test
  void exportsOnlyTypedOwnedTargetConfigurationAndExplicitAdmissionState() throws Exception {
    var snapshot = new TargetSnapshot(OwnedTarget.Kind.PRODUCT, 4190, INSTANCE, false);
    var report =
        EvidenceReport.of(Instant.EPOCH, Instant.EPOCH, "abcdef0", List.of(), List.of(snapshot));
    new EvidenceExporter().write(report, directory);
    var json =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(Files.readString(directory.resolve("report.json")));
    assertEquals(4190, json.path("targets").get(0).path("port").asInt());
    assertFalse(json.path("targets").get(0).path("markerMatched").asBoolean());
    assertEquals(
        INSTANCE.toString(), json.path("targets").get(0).path("expectedInstanceId").asString());
  }

  @Test
  void preservesSafeInternalErrorCategoryWithoutCopyingExceptionDetails() {
    var observed =
        SanitizedObservation.fromResponse(
            "POST",
            SanitizedObservation.Endpoint.HOLDS,
            500,
            Map.of("code", "INTERNAL_ERROR", "detail", "private SQL/password/session text"),
            4);
    assertEquals("INTERNAL_ERROR", observed.code());
    assertEquals(500, observed.status());
  }

  @Test
  void concurrentObservationRetainsActualResponseTimeAndFixedTargetPort() {
    Instant responseTime = Instant.parse("2026-11-18T12:01:02Z");
    var observed =
        SanitizedObservation.fromResponse(
            "POST",
            SanitizedObservation.Endpoint.HOLDS,
            409,
            Map.of("code", "SLOT_UNAVAILABLE"),
            4,
            responseTime,
            4192);
    assertEquals(responseTime, observed.observedAt());
    assertEquals(4192, observed.targetPort());
  }

  @Test
  void preservesTypedHoldDeadlineAndServerTimeForInspectableExpiryEvidence() throws Exception {
    var body = new java.util.HashMap<String, Object>();
    body.put("status", "EXPIRED");
    body.put("createdAt", "2026-11-18T12:00:00Z");
    body.put("expiresAt", "2026-11-18T12:02:00Z");
    body.put("serverNow", "2026-11-18T12:02:01Z");
    body.put("replayed", true);
    body.put("password", "private-synthetic-value");
    var observation =
        SanitizedObservation.fromResponse(
            "GET", SanitizedObservation.Endpoint.RESERVATION, 200, body, 4);
    assertEquals(Instant.parse("2026-11-18T12:02:00Z"), observation.expiresAt());
    assertEquals(Instant.parse("2026-11-18T12:02:01Z"), observation.serverNow());
    assertEquals(Boolean.TRUE, observation.replayed());
    var result =
        new ScenarioResult(
            CheckId.A08,
            Outcome.FAIL,
            Reason.ASSERTION_FAILED,
            List.of(observation),
            List.of(new StateAssertion(AssertionKind.EXPIRED_CONFIRMATION_DENIED, false)),
            Map.of());
    new EvidenceExporter()
        .write(
            EvidenceReport.of(Instant.EPOCH, Instant.EPOCH, "abcdef0", List.of(result)), directory);
    var exported =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(Files.readString(directory.resolve("report.json")))
            .path("results")
            .get(7)
            .path("observations")
            .get(0);
    assertEquals("2026-11-18T12:02:00Z", exported.path("expiresAt").asString());
    assertFalse(
        Files.readString(directory.resolve("report.json")).contains("private-synthetic-value"));
  }

  @Test
  void escapesMarkupAndWritesDigestForEachExportWithoutOverwritingHistory() throws Exception {
    assertEquals("&lt;script&gt;&amp;&quot;&#39;", EvidenceExporter.escapeHtml("<script>&\"'"));
    var report = EvidenceReport.of(Instant.EPOCH, Instant.EPOCH, "abcdef0", List.of());
    new EvidenceExporter().write(report, directory);
    for (String line : Files.readAllLines(directory.resolve("SHA256SUMS"))) {
      String[] parts = line.split("  ", 2);
      assertEquals(
          parts[0],
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(Files.readAllBytes(directory.resolve(parts[1])))));
    }
    assertThrows(
        java.nio.file.FileAlreadyExistsException.class,
        () -> new EvidenceExporter().write(report, directory));
  }

  @Test
  void validatesDatabaseEvidenceRatherThanTrustingPassLabel() throws Exception {
    Path file = directory.resolve("contention.json");
    Files.writeString(
        file,
        """
        {"kind":"DATABASE_CONTENTION","attempts":100,"workers":2,"processIds":[41,42],"outcomes":{"CREATED":2,"SLOT_UNAVAILABLE":98},"activeAssignments":2,"distinctReservations":2,"startedAt":"2026-11-18T12:00:00Z","finishedAt":"2026-11-18T12:00:02Z","revision":"abcdef0","result":"PASS"}
        """);
    assertEquals(Outcome.FAIL, DatabaseContentionEvidence.read(file, "abcdef0").outcome());
    Files.writeString(
        file,
        Files.readString(file)
            .replace("\"CREATED\":2", "\"CREATED\":1")
            .replace("\"SLOT_UNAVAILABLE\":98", "\"SLOT_UNAVAILABLE\":99")
            .replace("\"activeAssignments\":2", "\"activeAssignments\":1")
            .replace("\"distinctReservations\":2", "\"distinctReservations\":1"));
    assertEquals(Outcome.PASS, DatabaseContentionEvidence.read(file, "abcdef0").outcome());
    assertEquals(
        41L,
        DatabaseContentionEvidence.read(file, "abcdef0")
            .metrics()
            .get(Metric.DATABASE_PROCESS_ONE_ID));
    assertEquals(
        42L,
        DatabaseContentionEvidence.read(file, "abcdef0")
            .metrics()
            .get(Metric.DATABASE_PROCESS_TWO_ID));
    assertEquals(
        2000L,
        DatabaseContentionEvidence.read(file, "abcdef0")
            .metrics()
            .get(Metric.DATABASE_DURATION_MILLIS));
    assertEquals(Outcome.INCOMPLETE, DatabaseContentionEvidence.read(null, "abcdef0").outcome());
    assertEquals(Outcome.ERROR, DatabaseContentionEvidence.read(file, "badrevision").outcome());
  }
}
