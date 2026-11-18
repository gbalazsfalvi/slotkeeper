package io.github.gbalazsfalvi.slotkeeper.security;

import io.github.gbalazsfalvi.slotkeeper.core.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds bytes before parsing or password work, including chunked requests and form credentials.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class RequestBoundsFilter extends OncePerRequestFilter {
  private static final int MAX_BODY = 16 * 1024;
  private final ApiProblems problems;

  /**
   * Creates the byte and credential admission boundary.
   *
   * @param problems fixed safe error writer
   */
  public RequestBoundsFilter(ApiProblems problems) {
    this.problems = problems;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString();
    request.setAttribute("slotkeeper.requestId", requestId);
    response.setHeader("X-Request-Id", requestId);
    try {
      if (!Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())) {
        chain.doFilter(request, response);
        return;
      }
      if (request.getContentLengthLong() > MAX_BODY)
        throw new RuleViolation(ProblemCode.PAYLOAD_TOO_LARGE);
      byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
      if (body.length > MAX_BODY) throw new RuleViolation(ProblemCode.PAYLOAD_TOO_LARGE);
      Map<String, String[]> form = null;
      if ("/api/session".equals(request.getRequestURI())) {
        String content = request.getContentType();
        if (content == null
            || !content
                .toLowerCase(Locale.ROOT)
                .split(";", 2)[0]
                .strip()
                .equals("application/x-www-form-urlencoded"))
          throw new RuleViolation(ProblemCode.UNSUPPORTED_MEDIA_TYPE);
        if (request.getQueryString() != null) throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
        form = loginForm(body);
      }
      chain.doFilter(new CachedRequest(request, body, form), response);
    } catch (RuleViolation failure) {
      problems.write(request, response, failure.code());
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException failure) {
      problems.write(request, response, ApiProblems.classify(failure));
    }
  }

  private static Map<String, String[]> loginForm(byte[] body) {
    try {
      String encoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      Map<String, String[]> result = new HashMap<>();
      for (String pair : encoded.split("&", -1)) {
        String[] fields = pair.split("=", 2);
        if (fields.length != 2) throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
        String key = URLDecoder.decode(fields[0], StandardCharsets.UTF_8),
            value = URLDecoder.decode(fields[1], StandardCharsets.UTF_8);
        if (!Set.of("username", "password").contains(key)
            || result.putIfAbsent(key, new String[] {value}) != null)
          throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
      }
      if (result.size() != 2
          || !result.get("username")[0].matches("[a-z0-9._-]{1,80}")
          || result.get("password")[0].isEmpty()
          || result.get("password")[0].length() > 256)
        throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
      return Map.copyOf(result);
    } catch (IllegalArgumentException | CharacterCodingException failure) {
      throw new RuleViolation(ProblemCode.VALIDATION_ERROR);
    }
  }

  /** Replays only the bounded bytes; Spring's credential filter sees the validated form fields. */
  private static final class CachedRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    private final Map<String, String[]> form;

    CachedRequest(HttpServletRequest request, byte[] body, Map<String, String[]> form) {
      super(request);
      this.body = body;
      this.form = form;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream source = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return source.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) {
          return source.read(target, offset, length);
        }

        @Override
        public boolean isFinished() {
          return source.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Blocking bounded requests only.");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public String getParameter(String name) {
      return form == null
          ? super.getParameter(name)
          : form.containsKey(name) ? form.get(name)[0] : null;
    }

    @Override
    public String[] getParameterValues(String name) {
      return form == null
          ? super.getParameterValues(name)
          : form.containsKey(name) ? form.get(name).clone() : null;
    }

    @Override
    public Enumeration<String> getParameterNames() {
      return form == null ? super.getParameterNames() : Collections.enumeration(form.keySet());
    }

    @Override
    public Map<String, String[]> getParameterMap() {
      if (form == null) return super.getParameterMap();
      Map<String, String[]> copy = new HashMap<>();
      form.forEach((key, value) -> copy.put(key, value.clone()));
      return Collections.unmodifiableMap(copy);
    }
  }
}
