package io.github.gbalazsfalvi.slotkeeper.core.reservation;

import java.util.function.Function;

/**
 * Executes a complete reservation decision atomically; adapters supply real transactional
 * semantics.
 */
public interface BookingUnitOfWork {
  /**
   * Applies one unit of work, committing only if it returns successfully.
   *
   * @param work scoped reservation decision
   * @param <T> immutable result type
   * @return the committed result
   */
  <T> T execute(Function<BookingTransaction, T> work);
}
