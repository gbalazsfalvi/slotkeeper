# Delivery record

## Plan identity

Design: docs/superpowers/specs/2026-11-18-slotkeeper-design.md.
Plan: docs/superpowers/plans/2026-11-18-slotkeeper-v1.md.
Target: 18 November 2026, 23:59 Europe/Budapest.

## Current state

- Design and implementation plan written in English.
- Read-only builder review: no important unanswered implementation question found.
- Task0 foundation in progress. No product/test completion claimed.
- Task1â€“Task7 remain.

## Environment observed

Java21 is installed separately from the machine's default Java23; task commands explicitly select Java21. Node24.14.0 is installed. Docker Desktop29.8.1 is running with the local desktop-linux/npipe endpoint. Existing Docker workloads are not modified.

## Rulings

- Ruling: use the newly created dedicated Slotkeeper repository as the isolated workspace, with implementation on codex/slotkeeper-v1 after the initial plan commit â€” there is no existing Slotkeeper checkout to protect; using the ambient private repository's worktree tool would target the wrong project.
- Ruling: a local PostgreSQL-backed session/rate design avoids extra cache/identity-provider services in today's scope â€” auth is still handled by Spring Security; future OIDC is a separate adapter change.
- Ruling: strict request/ownership checks are mandatory even for idempotent replays â€” a stored identity is not authorization.

## Observed checks

Append exact commands, source revisions and outcomes after execution. Planned checks are not PASS evidence.

- Foundation dependency resolution completed successfully with Gradle9.2.0 on Java21; strict re-resolution is being checked.
- Official Gradle wrapper JAR SHA-256 matched423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9.
- PostgreSQL18.7 resolved to postgres@sha256:2ea8630df176ad090ec92136dee850397c5af8781e4bcf2e4162593691c61bbc.
- Ruling: pin jsdom29.0.1 because latest31.0.1 requires Node24.15 while this host is24.14; the checked29.0.1 engine range supports this host. No Node/global environment change is needed.

- Task1 first red/green cycle observed: twelve core policy/state tests initially failed compilation because production types did not exist; after implementation all12passed,0failures/errors/skips. Logs remain in ignored.local/. This is not yet the complete Task1 gate.
- Npm installation and the foundation React/TypeScript/Vite build passed on Node24.14 after the compatible jsdom pin. The initial shell is not described as a completed booking interface.
- Ruling: explicit dependency inventory is an uncached administrative build task. Run it with --no-configuration-cache; normal compile/test tasks may retain the user's cache setting. Strict verification remains enabled.
