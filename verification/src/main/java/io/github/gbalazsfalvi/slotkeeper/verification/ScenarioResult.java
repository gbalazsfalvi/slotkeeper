package io.github.gbalazsfalvi.slotkeeper.verification;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable case result that cannot declare PASS with a missing or failed assertion.
 *
 * @param id the frozen check identity
 * @param outcome the honest observed outcome
 * @param reason the allowlisted explanation
 * @param observations safe HTTP observations
 * @param assertions independently specified predicates
 * @param metrics numeric count evidence
 */
record ScenarioResult(
    CheckId id,
    Outcome outcome,
    Reason reason,
    List<SanitizedObservation> observations,
    List<StateAssertion> assertions,
    Map<Metric, Long> metrics,
    DatabaseStateObservation databaseState) {
  ScenarioResult(
      CheckId id,
      Outcome outcome,
      Reason reason,
      List<SanitizedObservation> observations,
      List<StateAssertion> assertions,
      Map<Metric, Long> metrics) {
    this(id, outcome, reason, observations, assertions, metrics, null);
  }

  /**
   * Copies and validates all exportable fields.
   *
   * @param id the check identity
   * @param outcome its outcome
   * @param reason the fixed reason
   * @param observations immutable HTTP evidence
   * @param assertions observed predicates
   * @param metrics count evidence
   */
  public ScenarioResult {
    Objects.requireNonNull(id);
    Objects.requireNonNull(outcome);
    Objects.requireNonNull(reason);
    observations = List.copyOf(observations);
    assertions = List.copyOf(assertions);
    metrics = Map.copyOf(metrics);
    if (metrics.values().stream().anyMatch(n -> n < 0))
      throw new IllegalArgumentException("Negative count");
    if (outcome == Outcome.PASS
        && (assertions.isEmpty() || assertions.stream().anyMatch(a -> !a.passed())))
      throw new IllegalArgumentException("PASS requires observed passing assertions");
    if ((databaseState != null && id != CheckId.S02)
        || (id == CheckId.S02 && outcome == Outcome.PASS && databaseState == null))
      throw new IllegalArgumentException("S02 requires its typed database observation");
  }

  /**
   * Creates an explicitly missing-prerequisite result.
   *
   * @param id the dependent check
   * @param reason the unavailable prerequisite
   * @return an incomplete case, never a pass
   */
  public static ScenarioResult incomplete(CheckId id, Reason reason) {
    return new ScenarioResult(id, Outcome.INCOMPLETE, reason, List.of(), List.of(), Map.of());
  }
}
