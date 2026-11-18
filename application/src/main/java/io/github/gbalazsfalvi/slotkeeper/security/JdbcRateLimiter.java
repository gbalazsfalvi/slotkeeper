package io.github.gbalazsfalvi.slotkeeper.security;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.core.RuleViolation;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Atomic fixed-minute admission shared by all product processes, with bounded row counters. */
@Component
public final class JdbcRateLimiter {
  private final JdbcTemplate sql;

  /**
   * Creates the shared database admission adapter.
   *
   * @param sql runtime database
   */
  public JdbcRateLimiter(JdbcTemplate sql) {
    this.sql = sql;
  }

  /**
   * Attempts one admission; an exhausted bucket never grows its counter beyond limit+1. This is an
   * independent short SQL transaction, completed before booking locks.
   *
   * @param kind fixed server-owned operation category
   * @param scope server-derived account/workspace or actual peer digest
   * @param limit maximum admitted attempts in a UTC fixed minute
   * @return seconds to retry, or zero when admitted
   */
  public int retryAfter(String kind, String scope, int limit) {
    var result =
        sql.queryForObject(
            """
            INSERT INTO rate_buckets(kind,scope_id,window_start,attempts) VALUES(?,?,date_trunc('minute',clock_timestamp()),1) ON CONFLICT(kind,scope_id,window_start)
            DO UPDATE SET attempts=least(rate_buckets.attempts+1,?+1)
            RETURNING attempts, greatest(1,ceil(extract(epoch
            FROM (window_start+interval '1 minute'-clock_timestamp())))) AS retry
            """,
            (r, n) -> new int[] {r.getInt("attempts"), r.getInt("retry")},
            kind,
            scope,
            limit);
    return result[0] > limit ? result[1] : 0;
  }

  /**
   * Checks current membership before allocating a mutation bucket for this scope.
   *
   * @param actor trusted session subject
   * @param workspace validated requested workspace
   * @return seconds until retry, or zero when admitted
   */
  public int mutationRetryAfter(UUID actor, UUID workspace) {
    Boolean allowed =
        sql.queryForObject(
            """
            SELECT EXISTS(SELECT 1
            FROM workspace_memberships m
            JOIN users u ON u.id=m.user_id
            WHERE m.user_id=?
            AND m.workspace_id=?
            AND m.active
            AND u.enabled)
            """,
            Boolean.class,
            actor,
            workspace);
    if (!Boolean.TRUE.equals(allowed)) throw new RuleViolation(ProblemCode.NOT_FOUND);
    return retryAfter("MUTATION", actor + ":" + workspace, 60);
  }
}
