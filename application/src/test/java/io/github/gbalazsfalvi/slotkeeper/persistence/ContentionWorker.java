package io.github.gbalazsfalvi.slotkeeper.persistence;

import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import io.github.gbalazsfalvi.slotkeeper.core.identity.Actor;
import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Independent JVM test worker, exercising the production use case and JDBC locking path. */
public final class ContentionWorker {
  private ContentionWorker() {}

  /**
   * Waits for a parent-controlled barrier, then submits fifty distinct actors. Credentials are
   * inherited privately in the environment, never command arguments or results.
   *
   * @param args worker number and private barrier directory
   * @throws Exception on missing prerequisites or infrastructure failure
   */
  public static void main(String[] args) throws Exception {
    int worker = Integer.parseInt(args[0]);
    Path directory = Path.of(args[1]);
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            System.getenv("SK_TEST_DB_URL"),
            System.getenv("SK_TEST_DB_USER"),
            System.getenv("SK_TEST_DB_PASSWORD"));
    ReservationService service =
        new ReservationService(new JdbcBookingUnitOfWork(source), BookingPolicy.defaults());
    Instant start = Instant.parse(System.getenv("SK_TEST_SLOT"));
    Files.writeString(
        directory.resolve("ready-" + worker), Long.toString(ProcessHandle.current().pid()));
    Instant deadline = Instant.now().plusSeconds(40);
    while (!Files.exists(directory.resolve("start"))) {
      if (Instant.now().isAfter(deadline))
        throw new IllegalStateException("Parent barrier timed out.");
      Thread.sleep(20);
    }
    List<String> outcomes = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Future<String>> futures = new ArrayList<>();
      for (int offset = 0; offset < 50; offset++) {
        int actorIndex = worker * 50 + offset;
        futures.add(
            pool.submit(
                () -> {
                  try {
                    service.createHold(
                        new Actor(DemoSeed.id("contender-" + actorIndex)),
                        DemoSeed.id("harbor"),
                        DemoSeed.id("harbor-oak"),
                        start,
                        "contender-" + actorIndex);
                    return "CREATED";
                  } catch (RuleViolation failure) {
                    return failure.code().name();
                  }
                }));
      }
      for (Future<String> future : futures) outcomes.add(future.get(45, TimeUnit.SECONDS));
    }
    Files.write(directory.resolve("outcomes-" + worker), outcomes);
  }
}
