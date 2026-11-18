package io.github.gbalazsfalvi.slotkeeper;

import io.github.gbalazsfalvi.slotkeeper.persistence.DemoSeed;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Local product composition. Teaching code is a separate artifact, never a runtime switch. */
@SpringBootApplication
@EnableScheduling
public class SlotkeeperApplication {
  /** Creates the framework composition root. */
  public SlotkeeperApplication() {}

  /**
   * Runs the web product or the isolated migration/fixture command.
   *
   * @param args optional single migrate argument; normal Spring arguments otherwise
   */
  public static void main(String[] args) {
    if (args.length == 1 && "health".equals(args[0])) {
      System.exit(localHealth());
      return;
    }
    if (args.length == 1 && "migrate".equals(args[0])) {
      Map<String, String> env = System.getenv();
      String url = required(env, "SLOTKEEPER_DB_URL");
      DriverManagerDataSource source =
          new DriverManagerDataSource(
              url,
              required(env, "SLOTKEEPER_MIGRATION_USER"),
              required(env, "SLOTKEEPER_MIGRATION_PASSWORD"));
      Flyway.configure()
          .dataSource(source)
          .defaultSchema("slotkeeper")
          .schemas("slotkeeper")
          .validateOnMigrate(true)
          .cleanDisabled(true)
          .load()
          .migrate();
      DemoSeed.populate(source);
      System.out.println("Slotkeeper schema and synthetic local fixtures are ready.");
      return;
    }
    SpringApplication.run(SlotkeeperApplication.class, args);
  }

  private static int localHealth() {
    try {
      String port = System.getenv().getOrDefault("PORT", "4190");
      var request =
          java.net.http.HttpRequest.newBuilder(
                  java.net.URI.create(
                      "http://127.0.0.1:" + Integer.parseInt(port) + "/actuator/health"))
              .timeout(java.time.Duration.ofSeconds(3))
              .GET()
              .build();
      try (var client =
          java.net.http.HttpClient.newBuilder()
              .connectTimeout(java.time.Duration.ofSeconds(2))
              .build()) {
        return client
                    .send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                    .statusCode()
                == 200
            ? 0
            : 1;
      }
    } catch (Exception unavailable) {
      return 1;
    }
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing local migration configuration: " + name);
    return value;
  }
}
