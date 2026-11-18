package io.github.gbalazsfalvi.slotkeeper.verification;

import java.io.IOException;

/** Fixed-owned-target local rehearsal entry point with sanitized, failure-preserving exports. */
public final class RehearsalMain {
  private RehearsalMain() {}

  /**
   * Runs the local exercise and exits nonzero for failures, errors or incomplete prerequisites.
   *
   * @param args explicit target origins, expected instance UUIDs, output directory and source
   *     revision
   */
  public static void main(String[] args) {
    System.exit(execute(args));
  }

  static int execute(String[] args) {
    final RehearsalOptions options;
    try {
      options = RehearsalOptions.parse(args);
    } catch (IllegalArgumentException exception) {
      System.err.println(
          "Invalid rehearsal configuration. Supply fixed loopback targets, their script-owned instance UUIDs, --output and --revision.");
      return 2;
    }
    return execute(options);
  }

  /** Runs an explicit internal target context; command-line parsing never accepts fixture ports. */
  static int execute(RehearsalOptions options) {
    EvidenceReport report = new ScenarioRunner(options).run();
    try {
      new EvidenceExporter().write(report, options.output());
    } catch (IOException | RuntimeException exception) {
      System.err.println(
          "Rehearsal evidence could not be written; existing histories are preserved.");
      return 2;
    }
    System.out.println(
        "Slotkeeper rehearsal: " + report.counts() + "; complete=" + report.complete());
    if (report.counts().get(Outcome.ERROR) > 0) return 2;
    if (report.counts().get(Outcome.FAIL) > 0) return 1;
    return report.complete() ? 0 : 3;
  }
}
