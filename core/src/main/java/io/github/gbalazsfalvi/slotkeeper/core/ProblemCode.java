package io.github.gbalazsfalvi.slotkeeper.core;

/** Stable domain and boundary failures; HTTP mapping belongs to the application adapter. */
public enum ProblemCode {
  /** A submitted value does not meet the published contract. */
  VALIDATION_ERROR,
  /** No authenticated actor is available. */
  AUTHENTICATION_REQUIRED,
  /** Authentication failed without disclosing account existence. */
  INVALID_CREDENTIALS,
  /** The current scoped role cannot perform this action. */
  FORBIDDEN,
  /** The browser request did not carry a valid CSRF token. */
  CSRF_INVALID,
  /** The object is unavailable to this actor and workspace. */
  NOT_FOUND,
  /** Another active reservation owns the slot. */
  SLOT_UNAVAILABLE,
  /** A declared booking allowance has been reached. */
  QUOTA_EXCEEDED,
  /** The hold deadline has been reached. */
  HOLD_EXPIRED,
  /** The requested state transition is not permitted. */
  INVALID_STATE,
  /** A retained idempotency identity refers to different input. */
  IDEMPOTENCY_CONFLICT,
  /** The bounded request body was exceeded. */
  PAYLOAD_TOO_LARGE,
  /** The request media type is not supported. */
  UNSUPPORTED_MEDIA_TYPE,
  /** The request budget for the current window was reached. */
  RATE_LIMITED,
  /** A bounded contention wait could not complete. */
  RESOURCE_BUSY,
  /** A required service failed; protection does not fail open. */
  SERVICE_UNAVAILABLE
}
