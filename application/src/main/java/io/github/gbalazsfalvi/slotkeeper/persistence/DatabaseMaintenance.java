package io.github.gbalazsfalvi.slotkeeper.persistence;

import javax.sql.DataSource;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded maintenance; missed cleanup never substitutes for logical expiry or current authority.
 */
@Component
@ConditionalOnProperty(
    name = "slotkeeper.maintenance-enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class DatabaseMaintenance {
  private static final Logger LOG = LoggerFactory.getLogger(DatabaseMaintenance.class);
  private final ExpiryWorker expiry;
  private final JdbcTemplate sql;
  private final DatabaseTime clock;

  /**
   * Creates bounded calendar, retry and expiry maintenance.
   *
   * @param source runtime-role database
   * @param clock shared independent decision time
   */
  public DatabaseMaintenance(DataSource source, DatabaseTime clock) {
    this.clock = clock;
    expiry = new ExpiryWorker(source, clock);
    sql = new JdbcTemplate(source);
  }

  /** Expires at most fifty holds per cycle; another instance can safely run the same task. */
  @Scheduled(fixedDelay = 1000, initialDelay = 2000)
  public void expiry() {
    try {
      expiry.expireBatch();
    } catch (org.springframework.dao.DataAccessException
        | io.github.gbalazsfalvi.slotkeeper.core.RuleViolation failure) {
      LOG.warn("Expiry maintenance deferred: database temporarily unavailable.");
    }
  }

  /** Extends the rolling calendar and removes only old traffic/expired retry metadata. */
  @Scheduled(fixedDelay = 900000, initialDelay = 1000)
  public void retention() {
    try {
      DemoSeed.provisionSlots(sql, clock);
      sql.update(
          "DELETE FROM rate_buckets WHERE window_start<clock_timestamp()-interval '2 minutes'");
      sql.update("DELETE FROM idempotency_records WHERE retained_until<clock_timestamp()");
    } catch (org.springframework.dao.DataAccessException
        | io.github.gbalazsfalvi.slotkeeper.core.RuleViolation failure) {
      LOG.warn("Calendar/retention maintenance deferred: database temporarily unavailable.");
    }
  }
}
