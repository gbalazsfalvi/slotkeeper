package io.github.gbalazsfalvi.slotkeeper.security;

import io.github.gbalazsfalvi.slotkeeper.core.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.*;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fixed safe errors shared by MVC and security filters; exception/SQL/request text is never echoed.
 */
@Component
@RestControllerAdvice
public final class ApiProblems {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ApiProblems.class);
  private final JsonMapper json;

  /**
   * Creates the safe problem writer.
   *
   * @param json strict application serializer
   */
  public ApiProblems(JsonMapper json) {
    this.json = json;
  }

  /**
   * Writes a safe filter-level error.
   *
   * @param request current request carrying the server-generated correlation ID
   * @param response response to finish
   * @param code fixed domain/admission category
   * @throws IOException when the connection closes
   */
  public void write(HttpServletRequest request, HttpServletResponse response, ProblemCode code)
      throws IOException {
    response.setStatus(status(code));
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    json.writeValue(response.getOutputStream(), body(request, code));
  }

  /**
   * Maps MVC failures without leaking the cause, submitted values or database statements.
   *
   * @param failure observed framework or application failure
   * @param request current request
   * @return bounded stable problem
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handle(Exception failure, HttpServletRequest request) {
    ProblemCode code = classify(failure);
    if (code == ProblemCode.INVALID_STATE) {
      // Only fixed source metadata and the server-generated request ID enter diagnostics.
      // Never log exception messages, submitted values, cookies, credentials or database
      // statements.
      StackTraceElement origin = failure.getStackTrace()[0];
      LOG.warn(
          "Transition rejected at {}.{}:{}; request={}",
          origin.getClassName(),
          origin.getMethodName(),
          origin.getLineNumber(),
          request.getAttribute("slotkeeper.requestId"));
    }
    return ResponseEntity.status(status(code))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body(request, code));
  }

  /**
   * Classifies failures without exposing exception or database text.
   *
   * @param failure observed failure
   * @return stable safe failure category
   */
  public static ProblemCode classify(Exception failure) {
    if (failure instanceof RuleViolation rule) return rule.code();
    if (failure instanceof HttpMediaTypeNotSupportedException)
      return ProblemCode.UNSUPPORTED_MEDIA_TYPE;
    if (failure instanceof NoResourceFoundException) return ProblemCode.NOT_FOUND;
    if (failure instanceof HttpMessageNotReadableException
        || failure instanceof MethodArgumentTypeMismatchException
        || failure instanceof ServletRequestBindingException
        || failure instanceof IllegalArgumentException) return ProblemCode.VALIDATION_ERROR;
    if (failure instanceof org.springframework.transaction.CannotCreateTransactionException
        || failure instanceof DataAccessResourceFailureException)
      return ProblemCode.SERVICE_UNAVAILABLE;
    if (failure instanceof org.springframework.transaction.TransactionTimedOutException
        || failure instanceof TransientDataAccessException) return ProblemCode.RESOURCE_BUSY;
    return ProblemCode.INTERNAL_ERROR;
  }

  private static int status(ProblemCode code) {
    return switch (code) {
      case VALIDATION_ERROR -> 400;
      case AUTHENTICATION_REQUIRED, INVALID_CREDENTIALS -> 401;
      case FORBIDDEN, CSRF_INVALID -> 403;
      case NOT_FOUND -> 404;
      case SLOT_UNAVAILABLE, QUOTA_EXCEEDED, HOLD_EXPIRED, INVALID_STATE, IDEMPOTENCY_CONFLICT ->
          409;
      case PAYLOAD_TOO_LARGE -> 413;
      case UNSUPPORTED_MEDIA_TYPE -> 415;
      case RATE_LIMITED -> 429;
      case RESOURCE_BUSY, SERVICE_UNAVAILABLE -> 503;
      case INTERNAL_ERROR -> 500;
    };
  }

  private static Map<String, Object> body(HttpServletRequest request, ProblemCode code) {
    String title = code.name().replace('_', ' ').toLowerCase(Locale.ROOT);
    title = Character.toUpperCase(title.charAt(0)) + title.substring(1);
    String detail =
        switch (code) {
          case SLOT_UNAVAILABLE -> "This slot is no longer available. Choose another time.";
          case HOLD_EXPIRED -> "This hold has expired. Choose an available time.";
          case QUOTA_EXCEEDED -> "Your workspace booking allowance has been reached.";
          case RATE_LIMITED -> "Please wait before trying again.";
          case IDEMPOTENCY_CONFLICT -> "This retry key was already used for a different request.";
          case INVALID_CREDENTIALS -> "The username or password is incorrect.";
          case AUTHENTICATION_REQUIRED -> "Sign in to continue.";
          case CSRF_INVALID -> "Refresh your session and try again.";
          case FORBIDDEN -> "This action requires a workspace administrator.";
          case NOT_FOUND -> "The requested resource is unavailable.";
          case VALIDATION_ERROR -> "Check the request fields and allowed values.";
          case PAYLOAD_TOO_LARGE -> "The request body exceeds the allowed size.";
          case UNSUPPORTED_MEDIA_TYPE -> "Use the documented request content type.";
          case INVALID_STATE -> "This reservation cannot make that transition.";
          case RESOURCE_BUSY -> "This resource is busy. Retry the same request shortly.";
          case SERVICE_UNAVAILABLE -> "A required local service is unavailable. Try again shortly.";
          case INTERNAL_ERROR -> "The request could not be completed.";
        };
    Object requestId = request.getAttribute("slotkeeper.requestId");
    return Map.of(
        "type",
        "urn:slotkeeper:problem:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-'),
        "title",
        title,
        "status",
        status(code),
        "code",
        code.name(),
        "detail",
        detail,
        "requestId",
        requestId == null ? UUID.randomUUID().toString() : requestId.toString());
  }
}
