# 0001 · Use a modular monolith

**Status:** accepted for v1.
**Date:** 18 November 2026.

## Context

The reference needs a useful booking interface, coherent transaction boundaries and inspectable security exercises. A challenge-only site would not demonstrate ordinary product behavior. Separate identity, booking, broker and cache services would add deployment boundaries without helping the capacity-one room invariant.

## Decision

Use one Spring Boot application with a packaged React interface and PostgreSQL. Keep immutable domain values, policies, use cases and ports in the framework-free `core` module. Put HTTP/security/JDBC composition in `application`. Keep `verification` and `training-target` as independent artifacts outside the application's dependency graph.

## Consequences

Reviewers can trace one request through an explicit core transaction port to the database. Product deployment needs one application image and one database, plus a one-shot migrator. The teaching exercise cannot be enabled by weakening a product switch.

This structure does not establish independent scaling or fault isolation between product features. Separate services remain a future choice if operational requirements justify them. The current goal is a local reference, not a distributed platform.
