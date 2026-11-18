package io.github.gbalazsfalvi.slotkeeper.verification;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Script-owned fixed-origin CLI configuration; absent optional targets remain explicit
 * prerequisites.
 */
record RehearsalOptions(
    OwnedTarget app,
    OwnedTarget app2,
    OwnedTarget training,
    Path output,
    String revision,
    Path contentionEvidence,
    DatabaseTarget databaseTarget) {
  RehearsalOptions(
      OwnedTarget app,
      OwnedTarget app2,
      OwnedTarget training,
      Path output,
      String revision,
      Path contentionEvidence) {
    this(app, app2, training, output, revision, contentionEvidence, null);
  }

  static RehearsalOptions parse(String[] args) {
    Set<String> allowed =
        Set.of(
            "--app",
            "--app-instance",
            "--app2",
            "--app2-instance",
            "--training",
            "--training-instance",
            "--output",
            "--revision",
            "--contention-evidence",
            "--database-url");
    Map<String, String> values = new HashMap<>();
    if (args.length % 2 != 0) throw new IllegalArgumentException("Expected option-value pairs");
    for (int i = 0; i < args.length; i += 2) {
      if (!allowed.contains(args[i]) || values.put(args[i], args[i + 1]) != null)
        throw new IllegalArgumentException("Unknown or duplicate option");
    }
    for (String name : Set.of("--app", "--app-instance", "--output", "--revision"))
      if (!values.containsKey(name))
        throw new IllegalArgumentException("Required owned-target option missing");
    String revision = values.get("--revision");
    if (!revision.matches("[a-fA-F0-9]{7,64}"))
      throw new IllegalArgumentException("Revision must be hexadecimal");
    OwnedTarget primary = target(values, "--app", "--app-instance", OwnedTarget.Kind.PRODUCT);
    OwnedTarget second = target(values, "--app2", "--app2-instance", OwnedTarget.Kind.PRODUCT);
    if (primary.origin().getPort() != 4190
        || (second != null
            && (second.origin().getPort() != 4192
                || second.expectedInstanceId().equals(primary.expectedInstanceId()))))
      throw new IllegalArgumentException(
          "Independent application targets must use distinct identities and fixed ports");
    return new RehearsalOptions(
        primary,
        second,
        target(values, "--training", "--training-instance", OwnedTarget.Kind.TRAINING),
        Path.of(values.get("--output")).toAbsolutePath().normalize(),
        revision,
        values.containsKey("--contention-evidence")
            ? Path.of(values.get("--contention-evidence"))
            : null,
        values.containsKey("--database-url")
            ? new DatabaseTarget(values.get("--database-url"))
            : null);
  }

  private static OwnedTarget target(
      Map<String, String> values, String origin, String identity, OwnedTarget.Kind kind) {
    if (values.containsKey(origin) != values.containsKey(identity))
      throw new IllegalArgumentException("Each target requires its script-owned instance identity");
    return values.containsKey(origin)
        ? new OwnedTarget(
            URI.create(values.get(origin)), UUID.fromString(values.get(identity)), kind)
        : null;
  }
}
