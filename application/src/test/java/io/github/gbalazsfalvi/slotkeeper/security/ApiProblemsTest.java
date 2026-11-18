package io.github.gbalazsfalvi.slotkeeper.security;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gbalazsfalvi.slotkeeper.core.ProblemCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resource timeouts remain retryable, and internal exception text never becomes response content.
 */
class ApiProblemsTest {
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
