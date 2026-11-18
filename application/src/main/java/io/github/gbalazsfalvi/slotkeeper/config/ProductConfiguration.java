package io.github.gbalazsfalvi.slotkeeper.config;

import io.github.gbalazsfalvi.slotkeeper.core.reservation.*;
import io.github.gbalazsfalvi.slotkeeper.persistence.*;
import javax.sql.DataSource;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

/** Explicit dependency wiring and strict JSON admission at the product boundary. */
@Configuration(proxyBeanMethods = false)
public class ProductConfiguration {
  /** Creates Spring's configuration object. */
  public ProductConfiguration() {}

  /**
   * Wires the reservation use cases to durable PostgreSQL transactions.
   *
   * @param source runtime database
   * @param clock shared independent decision-clock capability
   * @return durable booking use cases
   */
  @Bean
  public ReservationService reservations(DataSource source, DatabaseTime clock) {
    return new ReservationService(
        new JdbcBookingUnitOfWork(source, clock), BookingPolicy.defaults());
  }

  /**
   * Wires scoped read models to the runtime database.
   *
   * @param source runtime database
   * @param clock shared independent decision-clock capability
   * @return scoped read models
   */
  @Bean
  public BookingQueries bookingQueries(DataSource source, DatabaseTime clock) {
    return new JdbcBookingQueries(source, clock);
  }

  /**
   * Shares a bounded independent clock connection factory across product capabilities.
   *
   * @param source primary runtime connections
   * @return durable decision time with its own two-connection budget
   */
  @Bean
  public DatabaseTime databaseTime(DataSource source) {
    return new DatabaseTime(source);
  }

  /**
   * Rejects duplicate JSON keys before typed request canonicalization.
   *
   * @return parser rule rejecting ambiguous duplicate keys
   */
  @Bean
  public JsonFactoryBuilderCustomizer duplicateKeys() {
    return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  /**
   * Rejects unknown authority fields, trailing documents and null primitives.
   *
   * @return mapper rules rejecting unknown authority fields and trailing documents
   */
  @Bean
  public JsonMapperBuilderCustomizer strictJson() {
    return builder ->
        builder.enable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
  }
}
