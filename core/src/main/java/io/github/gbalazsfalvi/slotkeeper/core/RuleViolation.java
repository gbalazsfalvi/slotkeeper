package io.github.gbalazsfalvi.slotkeeper.core;

import java.io.Serial;
import java.util.Objects;

/** A safe machine-readable rejection, without request bodies or transport-specific details. */
public final class RuleViolation extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;
  private final ProblemCode code;

  /**
   * Creates a rejection with a stable code.
   *
   * @param code the contract failure, never a user-controlled error message
   */
  public RuleViolation(ProblemCode code) {
    super(Objects.requireNonNull(code).name());
    this.code = code;
  }

  /**
   * Returns the stable rejection category.
   *
   * @return the category for safe adapter mapping
   */
  public ProblemCode code() {
    return code;
  }
}
