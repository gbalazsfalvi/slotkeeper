package io.github.gbalazsfalvi.slotkeeper.verification;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Real Java HTTP session; cookies, synthetic passwords and CSRF values stay private in memory. */
final class ActorSession {
  private static final TypeReference<Map<String, Object>> BODY_TYPE = new TypeReference<>() {};
  private static final String FIXTURE_PASSWORD = "Slotkeeper-Local-Only!";
  private final OwnedTarget target;
  private final CookieManager cookies;
  private final HttpClient client;
  private final JsonMapper mapper = JsonMapper.builder().build();
  private String csrf;
  private UUID userId;

  ActorSession(OwnedTarget target) {
    this(target, new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER));
  }

  private ActorSession(OwnedTarget target, CookieManager cookies) {
    this.target = target;
    this.cookies = cookies;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(cookies)
            .build();
  }

  ActorSession at(OwnedTarget other) {
    var copy = new ActorSession(other, cookies);
    copy.csrf = csrf;
    copy.userId = userId;
    return copy;
  }

  ActorSession retainedCookieSnapshot(OwnedTarget other) {
    var copied = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    for (URI origin : cookies.getCookieStore().getURIs())
      for (HttpCookie cookie : cookies.getCookieStore().get(origin))
        copied.getCookieStore().add(origin, (HttpCookie) cookie.clone());
    var copy = new ActorSession(other, copied);
    copy.csrf = csrf;
    copy.userId = userId;
    return copy;
  }

  UUID userId() {
    return userId;
  }

  void verifyTarget() throws IOException, InterruptedException {
    var response = request("GET", "/api/instance", null, null, CsrfMode.NONE, Map.of());
    if (response.status() != 200)
      throw new IllegalArgumentException("Instance marker is unavailable");
    target.verifyMarker(response.body());
  }

  void refreshCsrf() throws IOException, InterruptedException {
    var response = request("GET", "/api/csrf", null, null, CsrfMode.NONE, Map.of());
    if (response.status() != 200
        || !"X-CSRF-TOKEN".equals(response.body().get("headerName"))
        || !(response.body().get("token") instanceof String value)
        || value.isBlank()
        || value.length() > 4096) throw new IOException("CSRF prerequisite unavailable");
    csrf = (String) response.body().get("token");
  }

  void login(String username) throws IOException, InterruptedException {
    refreshCsrf();
    String form =
        "username="
            + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(FIXTURE_PASSWORD, StandardCharsets.UTF_8);
    var response =
        send(
            "POST",
            "/api/session",
            form,
            "application/x-www-form-urlencoded",
            null,
            CsrfMode.CURRENT,
            Map.of());
    if (response.status() != 200 || !Boolean.TRUE.equals(response.body().get("authenticated")))
      throw new IOException("Synthetic login prerequisite unavailable");
    Object user = response.body().get("user");
    if (!(user instanceof Map<?, ?> userMap))
      throw new IOException("Session view prerequisite unavailable");
    userId = UUID.fromString(String.valueOf(userMap.get("id")));
    refreshCsrf();
  }

  Response request(
      String method, String path, String json, String key, CsrfMode mode, Map<String, String> extra)
      throws IOException, InterruptedException {
    return send(method, path, json, "application/json", key, mode, extra);
  }

  String json(Map<String, ?> value) {
    return mapper.writeValueAsString(value);
  }

  private Response send(
      String method,
      String path,
      String body,
      String mediaType,
      String key,
      CsrfMode mode,
      Map<String, String> extra)
      throws IOException, InterruptedException {
    if (!path.startsWith("/api/")
        || path.startsWith("//")
        || path.contains("#")
        || path.contains("\\")) throw new IllegalArgumentException("Unexpected request path");
    URI uri = target.origin().resolve(path);
    if (!target.origin().getAuthority().equals(uri.getAuthority()))
      throw new IllegalArgumentException("Target origin changed");
    var builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json");
    if (body != null) builder.header("Content-Type", mediaType);
    if (key != null) builder.header("Idempotency-Key", key);
    if (mode == CsrfMode.CURRENT && csrf != null) builder.header("X-CSRF-TOKEN", csrf);
    if (mode == CsrfMode.INVALID) builder.header("X-CSRF-TOKEN", "invalid-synthetic-token");
    extra.forEach(builder::header);
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    long started = System.nanoTime();
    HttpResponse<byte[]> response =
        client.send(builder.build(), ignored -> new BoundedBodySubscriber(65_536));
    byte[] bytes = response.body();
    Map<String, Object> parsed = bytes.length == 0 ? Map.of() : mapper.readValue(bytes, BODY_TYPE);
    return new Response(
        response.statusCode(),
        parsed,
        response.headers().firstValue("Retry-After").orElse(null),
        Duration.ofNanos(System.nanoTime() - started).toMillis(),
        Instant.now(),
        target.origin().getPort());
  }

  enum CsrfMode {
    CURRENT,
    NONE,
    INVALID
  }

  record Response(
      int status,
      Map<String, Object> body,
      String retryAfter,
      long latencyMillis,
      Instant observedAt,
      int targetPort) {
    Response {
      body = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(body));
    }

    String code() {
      Object value = body.get("code");
      return value instanceof String code ? code : "";
    }

    UUID id() {
      return UUID.fromString(String.valueOf(body.get("id")));
    }

    String state() {
      return String.valueOf(body.get("status"));
    }

    SanitizedObservation sanitized(String method, SanitizedObservation.Endpoint endpoint) {
      return SanitizedObservation.fromResponse(
          method, endpoint, status, body, latencyMillis, observedAt, targetPort);
    }
  }
}
