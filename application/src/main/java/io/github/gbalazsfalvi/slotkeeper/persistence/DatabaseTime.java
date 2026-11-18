package io.github.gbalazsfalvi.slotkeeper.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.github.gbalazsfalvi.slotkeeper.core.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A durable nondecreasing booking clock. Each observation commits independently before returning,
 * so a business transaction never retains the singleton clock lock or borrows a nested pool slot.
 * This is a logical timestamp fence, not a guarantee about physical elapsed time during clock
 * faults.
 */
public final class DatabaseTime {
  private static final String OBSERVE =
      """
      UPDATE slotkeeper.decision_clock
      SET last_observed=greatest(last_observed,clock_timestamp())
      WHERE id=1
      RETURNING last_observed
      """;
  private final DataSource independentConnections;
  private final Semaphore capacity = new Semaphore(2, true);

  /**
   * Copies connection settings without reusing a primary Hikari pool or a Spring-bound connection.
   * At most two additional clock connections exist per shared runtime instance.
   *
   * @param primary the configured Hikari or unpooled DriverManager data source
   */
  public DatabaseTime(DataSource primary) {
    String url, user, password;
    Properties properties = new Properties();
    if (primary instanceof HikariDataSource pool) {
      url = pool.getJdbcUrl();
      user = pool.getUsername();
      password = pool.getPassword();
      properties.putAll(pool.getDataSourceProperties());
    } else if (primary instanceof DriverManagerDataSource driver) {
      url = driver.getUrl();
      user = driver.getUsername();
      password = driver.getPassword();
      if (driver.getConnectionProperties() != null)
        properties.putAll(driver.getConnectionProperties());
    } else
      throw new IllegalArgumentException(
          "An independent PostgreSQL clock connection source is required.");
    DriverManagerDataSource source =
        new DriverManagerDataSource(boundedUrl(Objects.requireNonNull(url)), user, password);
    source.setConnectionProperties(properties);
    independentConnections = source;
  }

  /**
   * Obtains and commits a fresh observation; callers still obtain it after all business row locks.
   * No fallback to an earlier wall time is permitted if admission, connection or SQL work fails.
   *
   * @return the committed authoritative logical database timestamp
   * @throws RuleViolation with a bounded retryable service category when observation is unavailable
   */
  public Instant now() {
    boolean acquired = false;
    try {
      acquired = capacity.tryAcquire(3, TimeUnit.SECONDS);
      if (!acquired) throw new RuleViolation(ProblemCode.RESOURCE_BUSY);
      // Direct JDBC deliberately bypasses Spring's transaction-bound DataSourceUtils.
      try (Connection connection = independentConnections.getConnection()) {
        connection.setAutoCommit(true);
        Instant observed;
        try (PreparedStatement statement = connection.prepareStatement(OBSERVE)) {
          statement.setQueryTimeout(3);
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new RuleViolation(ProblemCode.SERVICE_UNAVAILABLE);
            observed = result.getObject(1, OffsetDateTime.class).toInstant();
          }
        }
        // All results are consumed and the autocommit statement is complete before returning.
        return observed;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new RuleViolation(ProblemCode.RESOURCE_BUSY);
    } catch (SQLException failure) {
      String state = failure.getSQLState();
      throw new RuleViolation(
          "55P03".equals(state) || "57014".equals(state)
              ? ProblemCode.RESOURCE_BUSY
              : ProblemCode.SERVICE_UNAVAILABLE);
    } finally {
      if (acquired) capacity.release();
    }
  }

  private static String boundedUrl(String url) {
    int separator = url.indexOf('?');
    String base = separator < 0 ? url : url.substring(0, separator);
    List<String> fields = new ArrayList<>();
    if (separator >= 0)
      for (String field : url.substring(separator + 1).split("&")) {
        String name = field.split("=", 2)[0];
        if (!Set.of("connecttimeout", "sockettimeout", "applicationname")
            .contains(name.toLowerCase(Locale.ROOT))) fields.add(field);
      }
    // pgJDBC URL values override Properties. Normalize the URL, rather than silently losing bounds.
    fields.add("connectTimeout=3");
    fields.add("socketTimeout=10");
    fields.add("ApplicationName=slotkeeper-clock");
    return base + "?" + String.join("&", fields);
  }
}
