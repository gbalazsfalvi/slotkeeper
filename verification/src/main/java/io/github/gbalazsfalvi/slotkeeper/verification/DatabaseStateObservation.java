package io.github.gbalazsfalvi.slotkeeper.verification;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate-only readback of the owned Compose database; no rows, identities or credentials are
 * representable.
 */
record DatabaseStateObservation(
    long activeReservationWithoutMatchingAssignment,
    long invalidAssignmentScope,
    long duplicateActivePerSlot,
    long actorsOverTwoHolds,
    long actorsOverFourConfirmationsPerLocalDate,
    long reservationsMissingCreatedEvent,
    long recentReservationsMissingCreateReceipt,
    boolean runtimeCanUpdateEvents,
    boolean runtimeCanDeleteEvents,
    boolean runtimeCanCreateSchema,
    boolean transactionReadOnly,
    int serverVersionNum,
    Instant checkedAtUTC) {
  DatabaseStateObservation {
    if (activeReservationWithoutMatchingAssignment < 0
        || invalidAssignmentScope < 0
        || duplicateActivePerSlot < 0
        || actorsOverTwoHolds < 0
        || actorsOverFourConfirmationsPerLocalDate < 0
        || reservationsMissingCreatedEvent < 0
        || recentReservationsMissingCreateReceipt < 0
        || serverVersionNum <= 0)
      throw new IllegalArgumentException("Invalid aggregate database observation");
    Objects.requireNonNull(checkedAtUTC);
  }

  List<StateAssertion> assertions() {
    return List.of(
        new StateAssertion(
            AssertionKind.DATABASE_ACTIVE_RESERVATIONS_ASSIGNED,
            activeReservationWithoutMatchingAssignment == 0),
        new StateAssertion(
            AssertionKind.DATABASE_ASSIGNMENT_SCOPE_VALID, invalidAssignmentScope == 0),
        new StateAssertion(
            AssertionKind.DATABASE_NO_DUPLICATE_ACTIVE_SLOTS, duplicateActivePerSlot == 0),
        new StateAssertion(AssertionKind.DATABASE_ACTIVE_HOLD_QUOTA_VALID, actorsOverTwoHolds == 0),
        new StateAssertion(
            AssertionKind.DATABASE_CONFIRMATION_QUOTA_VALID,
            actorsOverFourConfirmationsPerLocalDate == 0),
        new StateAssertion(
            AssertionKind.DATABASE_CREATED_EVENTS_COMPLETE, reservationsMissingCreatedEvent == 0),
        new StateAssertion(
            AssertionKind.DATABASE_RECENT_CREATE_RECEIPTS_COMPLETE,
            recentReservationsMissingCreateReceipt == 0),
        new StateAssertion(
            AssertionKind.DATABASE_RUNTIME_CANNOT_UPDATE_EVENTS, !runtimeCanUpdateEvents),
        new StateAssertion(
            AssertionKind.DATABASE_RUNTIME_CANNOT_DELETE_EVENTS, !runtimeCanDeleteEvents),
        new StateAssertion(
            AssertionKind.DATABASE_RUNTIME_CANNOT_CREATE_SCHEMA, !runtimeCanCreateSchema),
        new StateAssertion(
            AssertionKind.DATABASE_PROBE_TRANSACTION_READ_ONLY, transactionReadOnly));
  }

  Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(
        "activeReservationWithoutMatchingAssignment", activeReservationWithoutMatchingAssignment);
    fields.put("invalidAssignmentScope", invalidAssignmentScope);
    fields.put("duplicateActivePerSlot", duplicateActivePerSlot);
    fields.put("actorsOverTwoHolds", actorsOverTwoHolds);
    fields.put("actorsOverFourConfirmationsPerLocalDate", actorsOverFourConfirmationsPerLocalDate);
    fields.put("reservationsMissingCreatedEvent", reservationsMissingCreatedEvent);
    fields.put("recentReservationsMissingCreateReceipt", recentReservationsMissingCreateReceipt);
    fields.put("runtimeCanUpdateEvents", runtimeCanUpdateEvents);
    fields.put("runtimeCanDeleteEvents", runtimeCanDeleteEvents);
    fields.put("runtimeCanCreateSchema", runtimeCanCreateSchema);
    fields.put("transactionReadOnly", transactionReadOnly);
    fields.put("serverVersionNum", serverVersionNum);
    fields.put("checkedAtUTC", checkedAtUTC);
    return java.util.Collections.unmodifiableMap(fields);
  }
}
