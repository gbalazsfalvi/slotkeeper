package io.github.gbalazsfalvi.slotkeeper.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerSemanticsTest {
  @TempDir Path directory;

  @Test
  void unavailableOrUnownedTargetExportsAnHonestFailedPrerequisiteRun() throws Exception {
    int code =
        RehearsalMain.execute(
            new String[] {
              "--app",
              "http://127.0.0.1:4190",
              "--app-instance",
              "00000000-0000-0000-0000-000000000000",
              "--output",
              directory.toString(),
              "--revision",
              "abcdef0"
            });
    assertEquals(2, code);
    var report =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(java.nio.file.Files.readString(directory.resolve("report.json")));
    assertFalse(report.path("complete").asBoolean());
    assertEquals(0, report.path("counts").path("PASS").asInt());
    assertEquals(15, report.path("results").size());
  }

  @Test
  void explicitOwnedIdentitiesAreMandatoryAndUnknownCliOptionsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RehearsalOptions.parse(
                new String[] {
                  "--app",
                  "http://127.0.0.1:65500",
                  "--app-instance",
                  "df5c1ce3-36be-4736-ac91-fd1c9a139949",
                  "--output",
                  "report",
                  "--revision",
                  "abcdef0"
                }));
    assertThrows(
        IllegalArgumentException.class,
        () -> RehearsalOptions.parse(new String[] {"--trusted-fixture-port", "65500"}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RehearsalOptions.parse(
                new String[] {
                  "--app", "http://127.0.0.1:4190", "--output", "report", "--revision", "abcdef0"
                }));
    assertThrows(
        IllegalArgumentException.class,
        () -> RehearsalOptions.parse(new String[] {"--scan", "http://example.com"}));
    var options =
        RehearsalOptions.parse(
            new String[] {
              "--app",
              "http://127.0.0.1:4190",
              "--app-instance",
              "df5c1ce3-36be-4736-ac91-fd1c9a139949",
              "--output",
              "report",
              "--revision",
              "abcdef0"
            });
    assertEquals(4190, options.app().origin().getPort());
    assertNull(options.app2());
    assertNull(options.training());
  }

  @Test
  void failedObservedAssertionAndTransportErrorAreDifferentOutcomesWithPreservedEvidence() {
    var evidence = new CaseEvidence(CheckId.A03);
    var observation =
        SanitizedObservation.fromResponse(
            "POST",
            SanitizedObservation.Endpoint.CANCEL,
            200,
            java.util.Map.of("status", "CANCELLED"),
            4);
    evidence.observations.add(observation);
    evidence.expect(AssertionKind.FOREIGN_OBJECT_ABSENT, false);
    assertEquals(Outcome.FAIL, evidence.finish().outcome());
    assertEquals(Outcome.ERROR, evidence.error(Reason.TRANSPORT_ERROR).outcome());
    assertEquals(List.of(observation), evidence.error(Reason.TRANSPORT_ERROR).observations());
    assertEquals(Outcome.INCOMPLETE, new CaseEvidence(CheckId.A12).finish().outcome());
  }

  @Test
  void partialHttpBurstCannotPassEvenWhenTheObservedWinnerIsUnique() {
    assertEquals(
        Outcome.FAIL,
        HttpBurstSummary.classify(
                List.of(201, 409, 429), List.of("", "SLOT_UNAVAILABLE", "RATE_LIMITED"))
            .outcome());
    var statuses = new java.util.ArrayList<Integer>();
    var codes = new java.util.ArrayList<String>();
    statuses.add(201);
    codes.add("");
    for (int i = 0; i < 99; i++) {
      statuses.add(409);
      codes.add("SLOT_UNAVAILABLE");
    }
    assertEquals(Outcome.PASS, HttpBurstSummary.classify(statuses, codes).outcome());
    statuses.set(90, 500);
    codes.set(90, "");
    assertEquals(Outcome.FAIL, HttpBurstSummary.classify(statuses, codes).outcome());
  }

  @Test
  void transportFailureRetainsAllAttemptAccountingAndIsNotAProductControlFailure() {
    var statuses = new java.util.ArrayList<Integer>();
    var codes = new java.util.ArrayList<String>();
    statuses.add(201);
    codes.add("");
    for (int i = 0; i < 98; i++) {
      statuses.add(409);
      codes.add("SLOT_UNAVAILABLE");
    }
    var summary = HttpBurstSummary.classify(statuses, codes, 1);
    assertEquals(Outcome.ERROR, summary.outcome());
    assertEquals(100L, summary.metrics().get(Metric.HTTP_ATTEMPTS));
    assertEquals(99L, summary.metrics().get(Metric.HTTP_RESPONSES));
    assertEquals(1L, summary.metrics().get(Metric.HTTP_TRANSPORT_ERRORS));
    assertEquals(98L, summary.metrics().get(Metric.HTTP_CONFLICT));
  }

  @Test
  void quotaDenialsAreCountedAsHttpGuardsWhenAnActorAlreadyOwnsTwoHolds() {
    var statuses = new java.util.ArrayList<Integer>();
    var codes = new java.util.ArrayList<String>();
    statuses.add(201);
    codes.add("");
    for (int i = 0; i < 40; i++) {
      statuses.add(409);
      codes.add("QUOTA_EXCEEDED");
    }
    for (int i = 0; i < 59; i++) {
      statuses.add(409);
      codes.add("SLOT_UNAVAILABLE");
    }
    var result = HttpBurstSummary.classify(statuses, codes);
    assertEquals(Outcome.PASS, result.outcome());
    assertEquals(40L, result.metrics().get(Metric.HTTP_QUOTA_LIMITED));
    assertEquals(59L, result.metrics().get(Metric.HTTP_CONFLICT));
    assertEquals(1L, result.metrics().get(Metric.HTTP_CREATED));
    assertEquals(100L, result.metrics().get(Metric.HTTP_ATTEMPTS));
    assertEquals(0L, result.metrics().get(Metric.HTTP_UNEXPECTED));
    statuses.set(5, 400);
    assertEquals(Outcome.FAIL, HttpBurstSummary.classify(statuses, codes).outcome());
  }
}
