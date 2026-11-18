package io.github.gbalazsfalvi.slotkeeper.http;

import io.github.gbalazsfalvi.slotkeeper.persistence.DatabaseTime;
import io.github.gbalazsfalvi.slotkeeper.security.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/**
 * Minimal current-session and accidental-target marker endpoints, with no credential projection.
 */
@RestController
public final class SessionController {
  private final ActorResolver actors;
  private final DatabaseTime clock;
  private final UUID instanceId;

  /**
   * Creates the safe session projection and owned instance marker.
   *
   * @param actors current-account guard
   * @param clock authoritative database decision time
   * @param environment owned instance ID configuration
   */
  public SessionController(ActorResolver actors, DatabaseTime clock, Environment environment) {
    this.actors = actors;
    this.clock = clock;
    String configured = environment.getProperty("slotkeeper.instance-id");
    instanceId =
        configured == null || configured.isBlank()
            ? UUID.randomUUID()
            : UUID.fromString(configured);
  }

  /**
   * Projects the current session without password hashes or workspace authority.
   *
   * @param auth framework authentication
   * @return safe current session view
   */
  @GetMapping("/api/session")
  public Map<String, Object> session(Authentication auth) {
    Instant now = clock.now();
    if (auth == null || !(auth.getPrincipal() instanceof SessionPrincipal principal))
      return Map.of("authenticated", false, "serverNow", now);
    actors.require(auth);
    return Map.of(
        "authenticated",
        true,
        "user",
        Map.of("id", principal.id(), "displayName", principal.displayName()),
        "serverNow",
        now);
  }

  /**
   * Exposes the framework token only to the current same-origin session.
   *
   * @param token framework-owned deferred token
   * @return token only to this session's same-origin caller
   */
  @GetMapping("/api/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  /**
   * Returns an accidental-target marker, not a security attestation.
   *
   * @return public local instance marker; this is not an authentication attestation
   */
  @GetMapping("/api/instance")
  public Map<String, Object> instance() {
    return Map.of(
        "project", "Slotkeeper", "instanceId", instanceId, "mode", "LOCAL", "version", "0.1.0");
  }
}
