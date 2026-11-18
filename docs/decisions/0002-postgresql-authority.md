# 0002 · Make PostgreSQL the booking authority

**Status:** accepted for v1.
**Date:** 18 November 2026.

## Context

Two app processes must agree about the last available slot, actor allowances, expired holds and retried requests. A browser countdown, Java mutex or read-then-write check cannot coordinate those processes durably.

## Decision

Use PostgreSQL row locks, conditional assignment and scoped relational constraints. Lock the retry receipt when applicable, then the actor guard, room/slot and reservation rows in a consistent order. Read the durable nondecreasing database booking timeline after the relevant locks; [ADR 0005](0005-durable-booking-time.md) refines the original raw-wall-clock design. Commit reservation state, slot assignment, successful event and retry receipt together. Release assignments only with the expected reservation ID.

Use effective expiry on reads and safe bounded cleanup. Retain retry identity by workspace/actor/operation/key with a fingerprint of validated typed input; replay returns current authorized state. Keep shared rate admission in a short separate database transaction before booking locks.

## Consequences

Correctness survives independent application processes and retries. Real PostgreSQL and two-process contention evidence are required; H2, mocked ports and rate-rejected HTTP requests cannot prove these properties.

The database is a shared availability dependency. Lock/statement budgets bound waits but can yield retryable failures. App scoping and constraints do not protect against a trusted database administrator.
