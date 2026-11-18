package io.github.gbalazsfalvi.slotkeeper.verification;

import java.net.URI;
import java.util.UUID;

/**
 * Only typed public ownership metadata is admitted; an internal ephemeral fixture override is
 * explicit, and command-line targets always have a zero override. No free-form URI or launch
 * arguments are exported.
 */
record TargetSnapshot(
    OwnedTarget.Kind artifact,
    int port,
    UUID expectedInstanceId,
    boolean markerMatched,
    int trustedFixturePort) {
  TargetSnapshot(
      OwnedTarget.Kind artifact, int port, UUID expectedInstanceId, boolean markerMatched) {
    this(artifact, port, expectedInstanceId, markerMatched, 0);
  }

  TargetSnapshot {
    new OwnedTarget(
        URI.create("http://127.0.0.1:" + port), expectedInstanceId, artifact, trustedFixturePort);
  }
}
