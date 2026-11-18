package io.github.gbalazsfalvi.slotkeeper.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Independently validates the parent-owned PostgreSQL worker evidence instead of trusting its
 * label.
 *
 * @param outcome the validated outcome
 * @param assertions exact contention predicates
 * @param metrics independently retained numeric counts
 */
record DatabaseContentionEvidence(
    Outcome outcome, List<StateAssertion> assertions, Map<Metric, Long> metrics) {
  /**
   * Freezes the admitted typed evidence.
   *
   * @param outcome validation result
   * @param assertions observed predicates
   * @param metrics safe numeric fields
   */
  public DatabaseContentionEvidence {
    assertions = List.copyOf(assertions);
    metrics = Map.copyOf(metrics);
  }

  /**
   * Reads a bounded evidence artifact with exact revision and outcome accounting.
   *
   * @param path the parent-generated artifact, or null when missing
   * @param revision the current rehearsal revision
   * @return an honest PASS, FAIL, ERROR or INCOMPLETE result
   */
  public static DatabaseContentionEvidence read(Path path, String revision) {
    if (path == null || !Files.isRegularFile(path))
      return new DatabaseContentionEvidence(Outcome.INCOMPLETE, List.of(), Map.of());
    try {
      if (Files.size(path) > 65_536) return invalid();
      JsonNode root = JsonMapper.builder().build().readTree(Files.readString(path));
      if (!"DATABASE_CONTENTION".equals(root.path("kind").asString())
          || !revision.equals(root.path("revision").asString())) return invalid();
      Instant start = Instant.parse(root.path("startedAt").asString());
      Instant end = Instant.parse(root.path("finishedAt").asString());
      if (end.isBefore(start)
          || Duration.between(start, end).compareTo(Duration.ofSeconds(120)) >= 0) return invalid();
      Map<Metric, Long> counts = new EnumMap<>(Metric.class);
      long attempts = requiredLong(root, "attempts");
      long workers = requiredLong(root, "workers");
      long active = requiredLong(root, "activeAssignments");
      long distinct = requiredLong(root, "distinctReservations");
      JsonNode outcomes = root.path("outcomes");
      long created = 0;
      long conflicts = 0;
      long busy = 0;
      long total = 0;
      for (String name : outcomes.propertyNames()) {
        if (!Set.of("CREATED", "SLOT_UNAVAILABLE", "RESOURCE_BUSY").contains(name))
          return invalid();
        long count = requiredLong(outcomes, name);
        total += count;
        if (name.equals("CREATED")) created = count;
        if (name.equals("SLOT_UNAVAILABLE")) conflicts = count;
        if (name.equals("RESOURCE_BUSY")) busy = count;
      }
      JsonNode pids = root.path("processIds");
      boolean processes =
          workers == 2
              && pids.isArray()
              && pids.size() == 2
              && pids.get(0).isIntegralNumber()
              && pids.get(1).isIntegralNumber()
              && pids.get(0).canConvertToLong()
              && pids.get(1).canConvertToLong()
              && pids.get(0).asLong() > 0
              && pids.get(1).asLong() > 0
              && pids.get(0).asLong() != pids.get(1).asLong();
      counts.put(Metric.DATABASE_ATTEMPTS, attempts);
      counts.put(Metric.DATABASE_WORKERS, workers);
      counts.put(Metric.DATABASE_CREATED, created);
      counts.put(Metric.DATABASE_CONFLICT, conflicts);
      counts.put(Metric.DATABASE_RESOURCE_BUSY, busy);
      counts.put(Metric.DATABASE_ACTIVE_ASSIGNMENTS, active);
      counts.put(Metric.DATABASE_DISTINCT_RESERVATIONS, distinct);
      if (processes) {
        counts.put(Metric.DATABASE_PROCESS_ONE_ID, pids.get(0).asLong());
        counts.put(Metric.DATABASE_PROCESS_TWO_ID, pids.get(1).asLong());
      }
      if (start.toEpochMilli() < 0) return invalid();
      counts.put(Metric.DATABASE_DURATION_MILLIS, Duration.between(start, end).toMillis());
      counts.put(Metric.DATABASE_STARTED_EPOCH_MILLIS, start.toEpochMilli());
      counts.put(Metric.DATABASE_FINISHED_EPOCH_MILLIS, end.toEpochMilli());
      var checks = new ArrayList<StateAssertion>();
      checks.add(
          new StateAssertion(AssertionKind.DATABASE_100_ATTEMPTS, attempts == 100 && total == 100));
      checks.add(new StateAssertion(AssertionKind.DATABASE_TWO_INDEPENDENT_PROCESSES, processes));
      checks.add(
          new StateAssertion(
              AssertionKind.DATABASE_OUTCOMES_CLASSIFIED,
              total == attempts && busy == 0 && "PASS".equals(root.path("result").asString())));
      checks.add(
          new StateAssertion(
              AssertionKind.DATABASE_ONE_ACTIVE_ASSIGNMENT, active == 1 && created == 1));
      checks.add(
          new StateAssertion(AssertionKind.DATABASE_ONE_DISTINCT_RESERVATION, distinct == 1));
      return new DatabaseContentionEvidence(
          checks.stream().allMatch(StateAssertion::passed) ? Outcome.PASS : Outcome.FAIL,
          checks,
          counts);
    } catch (RuntimeException | java.io.IOException exception) {
      return invalid();
    }
  }

  private static long requiredLong(JsonNode node, String name) {
    JsonNode value = node.path(name);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
      throw new IllegalArgumentException("Invalid numeric evidence");
    return value.asLong();
  }

  private static DatabaseContentionEvidence invalid() {
    return new DatabaseContentionEvidence(Outcome.ERROR, List.of(), Map.of());
  }
}
