package io.github.gbalazsfalvi.slotkeeper.security;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Admission uses actual connection peer and trusted session identity; forwarded headers are
 * ignored.
 */
final class RateLimitFilter extends OncePerRequestFilter {
  private static final Pattern WORKSPACE =
      Pattern.compile("^/api/workspaces/([0-9a-fA-F-]{36})(?:/.*)?$");
  private final JdbcRateLimiter rates;
  private final ApiProblems problems;

  RateLimitFilter(JdbcRateLimiter rates, ApiProblems problems) {
    this.rates = rates;
    this.problems = problems;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    SessionPrincipal actor =
        auth != null && auth.getPrincipal() instanceof SessionPrincipal value ? value : null;
    String path = request.getRequestURI(), method = request.getMethod();
    int retry = 0;
    if ("POST".equals(method) && "/api/session".equals(path))
      retry = rates.retryAfter("LOGIN", peer(request), 20);
    else if ("GET".equals(method) && "/api/csrf".equals(path) && actor == null)
      retry = rates.retryAfter("ANONYMOUS_CSRF", peer(request), 60);
    else if (actor != null && Set.of("POST", "PATCH", "PUT", "DELETE").contains(method)) {
      Matcher match = WORKSPACE.matcher(path);
      if (match.matches())
        try {
          retry = rates.mutationRetryAfter(actor.id(), UUID.fromString(match.group(1)));
        } catch (IllegalArgumentException ignored) {
          /* MVC returns a fixed validation error. */
        }
    }
    if (retry > 0) {
      response.setHeader("Retry-After", Integer.toString(retry));
      problems.write(request, response, ProblemCode.RATE_LIMITED);
      return;
    }
    chain.doFilter(request, response);
  }

  private static String peer(HttpServletRequest request) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(request.getRemoteAddr().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required digest unavailable.", impossible);
    }
  }
}
