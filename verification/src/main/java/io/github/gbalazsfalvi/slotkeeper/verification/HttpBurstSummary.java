package io.github.gbalazsfalvi.slotkeeper.verification;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Classification of the HTTP guard layer, deliberately separate from PostgreSQL lock admission. */
record HttpBurstSummary(
    Outcome outcome, List<StateAssertion> assertions, Map<Metric, Long> metrics) {
  static HttpBurstSummary classify(List<Integer> statuses, List<String> codes) {
    return classify(statuses, codes, 0);
  }

  static HttpBurstSummary classify(
      List<Integer> statuses, List<String> codes, int transportErrors) {
    Map<Metric, Long> counts = new EnumMap<>(Metric.class);
    for (var metric :
        List.of(
            Metric.HTTP_CREATED,
            Metric.HTTP_REPLAYED,
            Metric.HTTP_CONFLICT,
            Metric.HTTP_QUOTA_LIMITED,
            Metric.HTTP_RATE_LIMITED,
            Metric.HTTP_RESOURCE_BUSY,
            Metric.HTTP_UNEXPECTED)) counts.put(metric, 0L);
    if (transportErrors < 0) throw new IllegalArgumentException("Negative transport failures");
    counts.put(Metric.HTTP_ATTEMPTS, (long) statuses.size() + transportErrors);
    counts.put(Metric.HTTP_RESPONSES, (long) statuses.size());
    counts.put(Metric.HTTP_TRANSPORT_ERRORS, (long) transportErrors);
    if (statuses.size() != codes.size())
      throw new IllegalArgumentException("Response arrays must align");
    for (int i = 0; i < statuses.size(); i++) {
      int status = statuses.get(i);
      String code = codes.get(i);
      Metric metric =
          status == 201 && code.isEmpty()
              ? Metric.HTTP_CREATED
              : status == 409 && code.equals("SLOT_UNAVAILABLE")
                  ? Metric.HTTP_CONFLICT
                  : status == 409 && code.equals("QUOTA_EXCEEDED")
                      ? Metric.HTTP_QUOTA_LIMITED
                      : status == 429 && code.equals("RATE_LIMITED")
                          ? Metric.HTTP_RATE_LIMITED
                          : status == 503 && code.equals("RESOURCE_BUSY")
                              ? Metric.HTTP_RESOURCE_BUSY
                              : Metric.HTTP_UNEXPECTED;
      counts.merge(metric, 1L, Long::sum);
    }
    var assertions =
        List.of(
            new StateAssertion(AssertionKind.EXACTLY_100_HTTP_OUTCOMES, statuses.size() == 100),
            new StateAssertion(
                AssertionKind.HTTP_OUTCOMES_CLASSIFIED, counts.get(Metric.HTTP_UNEXPECTED) == 0),
            new StateAssertion(
                AssertionKind.ONE_HTTP_WINNER, counts.get(Metric.HTTP_CREATED) == 1));
    return new HttpBurstSummary(
        transportErrors > 0
            ? Outcome.ERROR
            : assertions.stream().allMatch(StateAssertion::passed) ? Outcome.PASS : Outcome.FAIL,
        assertions,
        Map.copyOf(counts));
  }
}
