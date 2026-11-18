package io.github.gbalazsfalvi.slotkeeper.security;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resource timeouts remain retryable, and internal exception text never becomes response content.
 */
class ApiProblemsTest {
  @Test
  void unexpectedFailureDiagnosticsExcludeMessagesAndThrowablePayloads() {
    Logger logger = (Logger) LoggerFactory.getLogger(ApiProblems.class);
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    try {
      var failure =
          new IllegalStateException(
              "private-sentinel-message",
              new SQLException(
                  "private-sentinel-sql",
                  "08006",
                  new SQLException("private-sentinel-cause", "private-sentinel-state")));
      assertEquals(ProblemCode.INTERNAL_ERROR, ApiProblems.classify(failure));
      assertFalse(capture.list.isEmpty(), "Unknown failures need bounded, safe diagnostics.");
      String messages =
          capture.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .collect(java.util.stream.Collectors.joining("\n"));
      assertTrue(messages.contains("IllegalStateException"));
      assertTrue(messages.contains("08006"));
      assertFalse(messages.contains("private-sentinel"));
      assertTrue(capture.list.stream().allMatch(event -> event.getThrowableProxy() == null));
    } finally {
      logger.detachAppender(capture);
      capture.stop();
    }
  }

  @Test
  void transactionDeadlineIsARetryableResourceFailure() {
    assertEquals(
        ProblemCode.RESOURCE_BUSY,
        ApiProblems.classify(new TransactionTimedOutException("Synthetic transaction timeout.")));
  }

  @Test
  void unexpectedFailureDoesNotExposeInternalDetails() {
    var response =
        new ApiProblems(JsonMapper.builder().build())
            .handle(
                new IllegalStateException("private-sentinel-value"), new MockHttpServletRequest());
    assertEquals(500, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertFalse(response.getBody().toString().contains("private-sentinel-value"));
    assertEquals("INTERNAL_ERROR", response.getBody().get("code"));
  }
}
