package io.github.gbalazsfalvi.slotkeeper.security;

import io.github.gbalazsfalvi.slotkeeper.core.*;
import io.github.gbalazsfalvi.slotkeeper.core.identity.Actor;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Converts an authenticated server principal into core identity, rechecking account availability.
 */
@Component
public final class ActorResolver {
  private final JdbcTemplate sql;

  /**
   * Creates the current-account identity guard.
   *
   * @param sql runtime database
   */
  public ActorResolver(JdbcTemplate sql) {
    this.sql = Objects.requireNonNull(sql);
  }

  /**
   * Requires a current enabled account; no authority is accepted from request fields.
   *
   * @param authentication trusted Spring Security context
   * @return framework-free subject
   */
  public Actor require(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof SessionPrincipal principal))
      throw new RuleViolation(ProblemCode.AUTHENTICATION_REQUIRED);
    boolean enabled =
        Boolean.TRUE.equals(
            sql.queryForObject(
                "SELECT enabled FROM users WHERE id=?", Boolean.class, principal.id()));
    if (!enabled) throw new RuleViolation(ProblemCode.AUTHENTICATION_REQUIRED);
    return new Actor(principal.id());
  }
}
