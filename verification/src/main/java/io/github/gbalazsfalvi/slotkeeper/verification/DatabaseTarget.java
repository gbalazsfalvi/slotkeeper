package io.github.gbalazsfalvi.slotkeeper.verification;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Exact owned Compose database endpoint; credentials and arbitrary JDBC options are forbidden in
 * its URL.
 */
record DatabaseTarget(String url) {
  private static final String PREFIX = "jdbc:postgresql://127.0.0.1:55419/slotkeeper?";

  DatabaseTarget {
    options(url);
  }

  Properties connectionProperties(String password) {
    Map<String, String> options = options(url);
    Properties properties = new Properties();
    properties.setProperty("user", "slotkeeper_app");
    properties.setProperty("password", password);
    properties.setProperty("connectTimeout", options.getOrDefault("connectTimeout", "5"));
    properties.setProperty("socketTimeout", options.getOrDefault("socketTimeout", "20"));
    properties.setProperty("readOnlyMode", "transaction");
    return properties;
  }

  private static Map<String, String> options(String url) {
    if (url == null || !url.startsWith(PREFIX)) throw invalid();
    Map<String, String> parsed = new HashMap<>();
    for (String option : url.substring(PREFIX.length()).split("&", -1)) {
      String[] parts = option.split("=", -1);
      if (parts.length != 2
          || !Set.of("currentSchema", "connectTimeout", "socketTimeout").contains(parts[0])
          || parsed.putIfAbsent(parts[0], parts[1]) != null) throw invalid();
    }
    if (!"slotkeeper".equals(parsed.get("currentSchema"))) throw invalid();
    timeout(parsed.get("connectTimeout"), 10);
    timeout(parsed.get("socketTimeout"), 30);
    return Map.copyOf(parsed);
  }

  private static void timeout(String value, int maximum) {
    if (value != null && (!value.matches("[1-9][0-9]?") || Integer.parseInt(value) > maximum))
      throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Database target is outside the owned endpoint and option allowlist");
  }
}
