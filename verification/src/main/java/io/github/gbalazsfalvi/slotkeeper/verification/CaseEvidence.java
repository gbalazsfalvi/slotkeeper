package io.github.gbalazsfalvi.slotkeeper.verification;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Private mutable collector; only immutable typed snapshots enter the public report. */
final class CaseEvidence {
  private final CheckId id;
  final List<SanitizedObservation> observations = new ArrayList<>();
  final List<StateAssertion> assertions = new ArrayList<>();
  final Map<Metric, Long> metrics = new EnumMap<>(Metric.class);

  CaseEvidence(CheckId id) {
    this.id = id;
  }

  void expect(AssertionKind kind, boolean passed) {
    assertions.add(new StateAssertion(kind, passed));
  }

  ScenarioResult finish() {
    if (assertions.isEmpty()) return incomplete(Reason.PREREQUISITE_MISSING);
    boolean passed = assertions.stream().allMatch(StateAssertion::passed);
    return new ScenarioResult(
        id,
        passed ? Outcome.PASS : Outcome.FAIL,
        passed ? Reason.OBSERVED : Reason.ASSERTION_FAILED,
        observations,
        assertions,
        metrics);
  }

  ScenarioResult error(Reason reason) {
    return new ScenarioResult(id, Outcome.ERROR, reason, observations, assertions, metrics);
  }

  ScenarioResult incomplete(Reason reason) {
    return new ScenarioResult(id, Outcome.INCOMPLETE, reason, observations, assertions, metrics);
  }
}
