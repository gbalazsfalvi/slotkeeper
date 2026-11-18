package io.github.gbalazsfalvi.slotkeeper.security;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import io.github.gbalazsfalvi.slotkeeper.persistence.DatabaseTime;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Standard server sessions and framework CSRF, with bounded password work and scoped DB authority.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
  /** Creates the framework security wiring. */
  public SecurityConfiguration() {}

  /**
   * Looks up only enabled local identities. Workspace roles never become global login authorities.
   *
   * @param sql runtime database
   * @return a fresh identity lookup per login
   */
  @Bean
  public UserDetailsService users(JdbcTemplate sql) {
    return username ->
        sql
            .query(
                "SELECT id,username,display_name,password_hash FROM users WHERE username=? AND enabled",
                (r, n) ->
                    new SessionPrincipal(
                        r.getObject("id", UUID.class),
                        r.getString("username"),
                        r.getString("display_name"),
                        r.getString("password_hash")),
                username)
            .stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Identity unavailable."));
  }

  /**
   * Creates the maintained Argon2id implementation with bounded per-process verification work.
   *
   * @return Argon2id with a four-worker per-process budget and fixed documented cost
   */
  @Bean
  public PasswordEncoder passwords() {
    Argon2PasswordEncoder argon = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    return new BoundedPasswordEncoder(argon, 4);
  }

  /**
   * Declares cookie attributes explicitly for the shared JDBC session repository.
   *
   * @param environment local configuration
   * @return the HttpOnly, SameSite cookie serializer
   */
  @Bean
  public DefaultCookieSerializer cookieSerializer(Environment environment) {
    DefaultCookieSerializer cookies = new DefaultCookieSerializer();
    cookies.setCookieName("SLOTKEEPER_SESSION");
    cookies.setCookiePath("/");
    cookies.setUseHttpOnlyCookie(true);
    cookies.setSameSite("Lax");
    cookies.setUseSecureCookie(
        environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false));
    return cookies;
  }

  /**
   * Configures authenticated API access without a redirect-based login protocol.
   *
   * @param http framework security builder
   * @param users current local identities
   * @param passwords bounded maintained password implementation
   * @param problems safe error writer
   * @param rates shared admission counters
   * @param json response serializer
   * @param clock shared authoritative decision time
   * @return the complete product security chain
   * @throws Exception when framework configuration is invalid
   */
  @Bean
  public SecurityFilterChain productSecurity(
      HttpSecurity http,
      UserDetailsService users,
      PasswordEncoder passwords,
      ApiProblems problems,
      JdbcRateLimiter rates,
      JsonMapper json,
      DatabaseTime clock)
      throws Exception {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(passwords);
    http.authenticationProvider(provider)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/csrf",
                        "/api/session",
                        "/api/instance",
                        "/actuator/health",
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.svg",
                        "/licenses/**")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
        .requestCache(cache -> cache.disable())
        .sessionManagement(
            session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, failure) ->
                            problems.write(request, response, ProblemCode.AUTHENTICATION_REQUIRED))
                    .accessDeniedHandler(
                        (request, response, failure) ->
                            problems.write(
                                request,
                                response,
                                failure instanceof CsrfException
                                    ? ProblemCode.CSRF_INVALID
                                    : ProblemCode.FORBIDDEN)))
        .formLogin(
            login ->
                login
                    .loginProcessingUrl("/api/session")
                    .successHandler(
                        (request, response, authentication) -> {
                          SessionPrincipal principal =
                              (SessionPrincipal) authentication.getPrincipal();
                          response.setContentType("application/json");
                          response.setHeader("Cache-Control", "no-store");
                          json.writeValue(
                              response.getOutputStream(),
                              Map.of(
                                  "authenticated",
                                  true,
                                  "user",
                                  Map.of(
                                      "id", principal.id(), "displayName", principal.displayName()),
                                  "serverNow",
                                  clock.now()));
                        })
                    .failureHandler(
                        (request, response, failure) -> {
                          boolean unavailable = failure instanceof AuthenticationServiceException;
                          if (unavailable) response.setHeader("Retry-After", "1");
                          problems.write(
                              request,
                              response,
                              unavailable
                                  ? ProblemCode.SERVICE_UNAVAILABLE
                                  : ProblemCode.INVALID_CREDENTIALS);
                        }))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("SLOTKEEPER_SESSION")
                    .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(
                        csp ->
                            csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"))
                    .frameOptions(frame -> frame.deny())
                    .addHeaderWriter(
                        new StaticHeadersWriter(
                            "Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
                    .addHeaderWriter(new StaticHeadersWriter("Referrer-Policy", "no-referrer")))
        .addFilterBefore(new RateLimitFilter(rates, problems), CsrfFilter.class);
    return http.build();
  }
}
