package io.github.gbalazsfalvi.slotkeeper.training;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Native loopback defaults; explicit wildcard binding exists for the isolated teaching container
 * only.
 */
record TrainingTargetOptions(UUID instanceId, String bindAddress, int port) {
  static TrainingTargetOptions parse(String[] args) {
    Map<String, String> values = new HashMap<>();
    if (args.length % 2 != 0) throw new IllegalArgumentException("Expected option-value pairs");
    for (int i = 0; i < args.length; i += 2) {
      if (!Set.of("--instance-id", "--bind-address", "--port").contains(args[i])
          || values.put(args[i], args[i + 1]) != null)
        throw new IllegalArgumentException("Unknown or duplicate teaching option");
    }
    if (!values.containsKey("--instance-id"))
      throw new IllegalArgumentException("Explicit owned instance identity required");
    String bind = values.getOrDefault("--bind-address", "127.0.0.1");
    int port = Integer.parseInt(values.getOrDefault("--port", "4193"));
    if (!Set.of("127.0.0.1", "0.0.0.0").contains(bind) || port != 4193)
      throw new IllegalArgumentException(
          "Teaching listener must use its fixed isolated address and port");
    return new TrainingTargetOptions(UUID.fromString(values.get("--instance-id")), bind, port);
  }
}
