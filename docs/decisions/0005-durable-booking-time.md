# 0005 — Durable nondecreasing booking time

Status: accepted. Date: 18 November 2026.

## Trigger

Real browser cancellation tests exposed an intermittent `INVALID_STATE`. Safe JDBC diagnostics captured a decision timestamp of `12:20:28.593426Z` against a committed creation timestamp of `12:20:30.843699Z`: an earlier observation by 2.250273 seconds. This establishes the inconsistent observations; it does not identify an NTP, hypervisor or operating-system root cause.

The core correctly rejects a transition before its own creation. Removing that guard would hide the fault. There is a second risk: an effective read of an expired but not yet cleaned-up hold could return to `HELD` after a backward time observation.

## Decision

Persist a singleton `decision_clock.last_observed`. An atomic PostgreSQL update returns:

```text
next decision time = max(previous committed decision time, current wall time)
```

The database serializes updates to this row, and a trigger rejects decreases. The runtime role can select the singleton and update its timestamp; it cannot insert or remove it. V002 initializes it from wall time and committed reservation creation, confirmation and event timestamps. Future slot starts, ends and hold deadlines are excluded.

The observation uses a genuinely independent, short autocommit JDBC connection. Its result set, statement and connection close before the capability returns. It never holds the clock-row lock across later business SQL, and never borrows another connection from a fully occupied primary pool. One shared runtime capability permits two such connections, with bounded admission, connection, statement and socket waits. JDBC URL timeout parameters are normalized because they override driver properties. There is no fallback to an earlier wall time when the observation fails.

Booking mutations still observe time **after** actor, room, slot and reservation locks. Booking detail, availability, calendar provisioning and session time projections use the same clock. The read-only S02 proof uses the persisted singleton value in its repeatable-read snapshot; its `checkedAtUTC` is this authoritative booking decision time, not a new unpersisted wall-clock observation.

Expiry cleanup takes room, then slot, then reservation locks, matching the business path. It skips a busy room before acquiring a slot. This also avoids a room/slot cycle through the event table's room foreign key.

## Consequences and limits

- A fence-backed observation cannot move backwards across application processes or restarts. Observed expiry cannot be undone merely by a later earlier wall-clock value.
- Time observation survives a rolled-back booking transaction. It is infrastructure metadata, not an applied reservation effect or quota charge.
- During a clock regression, logical time can pause until wall time catches up. The hold deadline remains exactly 120 timestamp seconds after creation, capped at slot start. This is not an unconditional guarantee of 120 physical elapsed seconds under clock faults.
- A singleton adds a shared write hotspot and short-lived connections. This deliberate local reference tradeoff needs measurement and redesign before a large deployment.
- Framework session idle expiry, UTC fixed-window rate limits and metadata retention remain separate wall-time mechanisms. The fence is a booking-time guarantee, not a replacement for all system clocks.
- V1 read-only observations that were never persisted cannot be reconstructed retrospectively. The non-revival guarantee starts with fence-backed observations.

## Verification

The PostgreSQL regressions cover future committed bootstrap history, hold/confirm/cancel while wall time trails the fence, observed expiry without physical cleanup, observation surviving rollback, forbidden timestamp regression/removal, a one-connection primary pool, and a blocked clock row's bounded failure. A separate busy-room regression first timed out, then verifies expiry skips without taking a slot lock. Final observed command outcomes belong in [DELIVERY.md](../DELIVERY.md).

Primary references: [PostgreSQL current-time functions](https://www.postgresql.org/docs/18/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT), [PostgreSQL transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html), and [pgJDBC connection properties](https://jdbc.postgresql.org/documentation/use/).
