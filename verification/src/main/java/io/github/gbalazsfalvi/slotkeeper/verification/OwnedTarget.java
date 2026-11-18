package io.github.gbalazsfalvi.slotkeeper.verification;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Restricts exercise targets to explicit script-owned instance identities on fixed loopback ports.
 * The public marker prevents accidents; it is not a security attestation.
 *
 * @param origin the strict IPv4 loopback origin
 * @param expectedInstanceId the identity supplied by the owning launch script
 * @param kind the separate product or teaching artifact
 * @param trustedFixturePort explicitly owned internal fixture port, or zero for normal fixed ports;
 *     command-line parsing never supplies this override
 */
record OwnedTarget(URI origin, UUID expectedInstanceId, Kind kind, int trustedFixturePort) {
  /** Keeps normal CLI and script targets on the fixed product/teaching ports. */
  OwnedTarget(URI origin, UUID expectedInstanceId, Kind kind) {
    this(origin, expectedInstanceId, kind, 0);
  }

  /**
   * Rejects arbitrary scan URLs and missing ownership identities before any network request.
   *
   * @param origin a loopback HTTP origin
   * @param expectedInstanceId the launch script's expected identity
   * @param kind expected artifact type
   * @param trustedFixturePort explicit internal fixture port, or zero for fixed CLI admission
   */
  public OwnedTarget {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(expectedInstanceId);
    Objects.requireNonNull(kind);
    if (!"http".equals(origin.getScheme())
        || !"127.0.0.1".equals(origin.getHost())
        || origin.getRawUserInfo() != null
        || origin.getRawQuery() != null
        || origin.getRawFragment() != null
        || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
        || !(trustedFixturePort == 0
            ? (kind == Kind.PRODUCT ? Set.of(4190, 4192) : Set.of(4193)).contains(origin.getPort())
            : trustedFixturePort >= 1024
                && trustedFixturePort <= 65535
                && origin.getPort() == trustedFixturePort))
      throw new IllegalArgumentException("Target is not an allowlisted owned loopback origin");
  }

  /**
   * Checks the exact marker against an independently supplied identity.
   *
   * @param marker parsed response without retaining it in a report
   * @throws IllegalArgumentException if artifact or instance differs
   */
  public void verifyMarker(Map<String, Object> marker) {
    String project = kind == Kind.PRODUCT ? "Slotkeeper" : "Slotkeeper Teaching Target";
    String mode = kind == Kind.PRODUCT ? "LOCAL" : "TRAINING";
    if (!project.equals(marker.get("project"))
        || !mode.equals(marker.get("mode"))
        || !"0.1.0".equals(marker.get("version"))
        || !expectedInstanceId.toString().equals(marker.get("instanceId")))
      throw new IllegalArgumentException("Owned instance marker did not match");
  }

  /** Separates the hardened product from the intentionally vulnerable artifact. */
  public enum Kind {
    PRODUCT,
    TRAINING
  }
}
