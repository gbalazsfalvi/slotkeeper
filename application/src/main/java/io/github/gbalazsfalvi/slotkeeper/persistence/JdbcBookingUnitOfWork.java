package io.github.gbalazsfalvi.slotkeeper.persistence;

import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A real database transaction owns every lock, event and retry receipt for one business decision.
 */
public final class JdbcBookingUnitOfWork implements BookingUnitOfWork {
  private final JdbcTemplate sql;
  private final DatabaseTime clock;
  private final TransactionTemplate transactions;

  /**
   * Uses one shared transaction manager for this data source.
   *
   * @param source runtime-role connections
   */
  public JdbcBookingUnitOfWork(DataSource source) {
    this(source, new DatabaseTime(source));
  }

  /**
   * Shares the runtime clock budget while business work retains its own transaction.
   *
   * @param source primary runtime-role connections
   * @param clock independent durable time observations
   */
  public JdbcBookingUnitOfWork(DataSource source, DatabaseTime clock) {
    this.clock = Objects.requireNonNull(clock);
    sql = new JdbcTemplate(source);
    transactions = new TransactionTemplate(new JdbcTransactionManager(source));
    transactions.setTimeout(15);
  }

  @Override
  public <T> T execute(Function<BookingTransaction, T> work) {
    return transactions.execute(
        status -> {
          // LOCAL settings disappear on commit/rollback; no leaked pooled-connection state.
          sql.execute("SET LOCAL lock_timeout='5s'");
          sql.execute("SET LOCAL statement_timeout='10s'");
          return Objects.requireNonNull(work.apply(new JdbcBookingTransaction(sql, clock)));
        });
  }
}
