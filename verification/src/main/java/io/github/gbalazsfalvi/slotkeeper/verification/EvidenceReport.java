package io.github.gbalazsfalvi.slotkeeper.verification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete case inventory including explicitly unobserved checks.
 *
 * @param startedAt rehearsal start
 * @param finishedAt rehearsal finish
 * @param revision exact caller-supplied source revision
 * @param results every frozen case in display order
 */
record EvidenceReport(
    Instant startedAt,
    Instant finishedAt,
    String revision,
    List<ScenarioResult> results,
    List<TargetSnapshot> targets) {
  /**
   * Validates safe metadata and freezes the result inventory.
   *
   * @param startedAt start instant
   * @param finishedAt finish instant
   * @param revision hexadecimal source revision
   * @param results all case results
   */
  public EvidenceReport {
    Objects.requireNonNull(startedAt);
    Objects.requireNonNull(finishedAt);
    if (finishedAt.isBefore(startedAt)
        || revision == null
        || !revision.matches("[a-fA-F0-9]{7,64}"))
      throw new IllegalArgumentException("Invalid report metadata");
    results = List.copyOf(results);
    targets = List.copyOf(targets);
    if (results.stream().map(ScenarioResult::id).distinct().count() != results.size())
      throw new IllegalArgumentException("Duplicate check inventory");
  }

  EvidenceReport(Instant start, Instant end, String revision, List<ScenarioResult> results) {
    this(start, end, revision, results, List.of());
  }

  /**
   * Fills missing mandatory checks with INCOMPLETE rather than silently omitting them.
   *
   * @param start start instant
   * @param end finish instant
   * @param revision hexadecimal source revision
   * @param observed actually observed results
   * @return immutable full inventory
   */
  public static EvidenceReport of(
      Instant start, Instant end, String revision, List<ScenarioResult> observed) {
    return of(start, end, revision, observed, List.of());
  }

  static EvidenceReport of(
      Instant start,
      Instant end,
      String revision,
      List<ScenarioResult> observed,
      List<TargetSnapshot> targets) {
    Map<CheckId, ScenarioResult> indexed = new EnumMap<>(CheckId.class);
    for (var result : observed)
      if (indexed.put(result.id(), result) != null)
        throw new IllegalArgumentException("Duplicate case");
    var all = new ArrayList<ScenarioResult>();
    for (var id : CheckId.values())
      all.add(indexed.getOrDefault(id, ScenarioResult.incomplete(id, Reason.PREREQUISITE_MISSING)));
    return new EvidenceReport(start, end, revision, all, targets);
  }

  /** Returns true only if every required case was observed and passed. */
  public boolean complete() {
    return results.size() == CheckId.values().length
        && results.stream().allMatch(r -> r.outcome() == Outcome.PASS);
  }

  /** Returns observed outcome counts including zero counts. */
  public Map<Outcome, Long> counts() {
    Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
    for (var outcome : Outcome.values())
      counts.put(outcome, results.stream().filter(r -> r.outcome() == outcome).count());
    return Map.copyOf(counts);
  }
}
