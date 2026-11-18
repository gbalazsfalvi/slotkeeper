# Slotkeeper v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. If bounded independent implementation tasks are delegated under an applicable skill, preserve the same contracts and review the integrated result. Steps use checkbox syntax for tracking.

**Goal:** deliver today's complete local reservation/security reference, with real product journeys and independently observed abuse evidence.

**Architecture:** framework-free Java core; one Spring Boot application with scoped JDBC transactions, shared database sessions and packaged React UI; independent verification and teaching artifacts. PostgreSQL is the concurrency/identity-state boundary, not a JVM lock or browser decision.

**Tech Stack:** Java21, Gradle9.2.0, SpringBoot4.1.2, PostgreSQL18.7, React19.3.1, TypeScript5.9.3, Vite8.3.8, Node24, Playwright1.65.1. Exact companion versions/image digests are pinned during foundation.

**Spec:** docs/superpowers/specs/2026-11-18-slotkeeper-design.md. Read it and GOAL.md first. The design's detail wins over a shorthand task description.

## Global Constraints

- Everything authored for the product/reports is English. Java21; SpringBoot4.1.2; no framework dependency in core.
- Hold TTL120seconds; capacity one; fixed30minute half-open slots; seven-day horizon; active-hold allowance two; successful-confirmation allowance four per actor/workspace/slot-local-date, without cancellation refund.
- Current workspace membership and ownership are authoritative; foreign objects404, scoped member/admin denial403, anonymous401.
- Idempotency identity is workspace+actor+operation+key; key8–128 allowlisted ASCII characters; different typed input409.
- Mutations60/minute per actor/workspace; login20/minute per actual peer; JSON16KiB; page size1–50; explicit fixed-window semantics; no trusted client forwarding headers.
- Core coverage at least90%line/85%branch. Use real PostgreSQL for database claims. No invented PASS or remote-CI claim.
- Default app has no vulnerable switch. Teaching code is a separate artifact/runtime and is not in app dependencies.
- Local owned Docker resources only. Normal app4190, second verifier app4192, explicit teaching fixture4193. Existing workloads/sites/repos remain untouched.
- Actual deadline target18November2026,23:59Europe/Budapest; do not waive requirements to claim timeliness.

## Review Focus

1. A lock wait crossing hold expiry must use fresh database time and must not confirm an expired hold. Pin in Tasks2/3 and A08.
2. A cancellation/retry for an old reservation must never release a replacement assignment. Pin in Task2 and A09.
3. A cached session or idempotency key must not bypass changed workspace permission. Pin in Tasks3/4 and A06.
4. A test blocked by rate limiting must not be counted as a database contention attempt. Pin separate DB/HTTP layers in Tasks2/6.
5. A missing/failed prerequisite must produce ERROR/INCOMPLETE, and raw credentials must never enter evidence. Pin in Task6 exporter tests.

## File ownership and public interfaces

- core/src/main/java/io/github/gbalazsfalvi/slotkeeper/core/: domain, authorization and reservation use cases/ports.
- application/src/main/java/io/github/gbalazsfalvi/slotkeeper/: Spring composition plus reservation, workspace, identity, security, activity and persistence features.
- application/src/main/resources/db/migration/: additive schema and synthetic-local seed migrations/initialization.
- web/src/: typed API adapter, session/CSRF state, workspace selection, availability, reservation summary, personal bookings and admin views.
- verification/src/: independent scenario runner, observed assertions, sanitized evidence export and owned-target admission.
- training-target/src/: local missing-object-scope positive control, isolated from product.
- docs/API.md owns exact response property names; docs/THREAT_MODEL.md owns trust boundaries; docs/DELIVERY.md records observed checks rather than plans.

Freeze these core interfaces before adapters diverge; small refinements must update all consumers in one reviewed change:

- Actor(UUID userId): trusted identity supplied by the authenticated HTTP adapter.
- ReservationStatus: HELD, CONFIRMED, CANCELLED, EXPIRED.
- BookingPolicy: validate slot/horizon, validate hold/confirmation/cancellation, and compute effective state for an explicit Instant now.
- BookingUnitOfWork.execute(Work<T>): run one callback against a BookingTransaction atomically; generic interface is framework-free.
- BookingTransaction: resolve scoped membership; lock scoped idempotency; lock actor guard, slot and reservation in order; obtain fresh DB time; read quota counts; persist reservation/assignment/idempotency/event together.
- ReservationService.createHold(Actor, UUID workspaceId, UUID roomId, Instant startAt, String idempotencyKey): ReservationView.
- ReservationService.confirm(Actor, UUID workspaceId, UUID reservationId, String idempotencyKey): ReservationView.
- ReservationService.cancel(Actor, UUID workspaceId, UUID reservationId): ReservationView.
- BookingQueries: read authorized workspaces/rooms, scoped availability, a scoped reservation and own bookings using an explicit actor.
- RuleViolation: stable ProblemCode; HTTP status belongs to application mapping, not core.
- API ReservationView fields: id, workspaceId, roomId, ownerId, startAt, endAt, status, createdAt, expiresAt, confirmedAt, serverNow, replayed. Nullable timestamps are documented. No password/session/internal SQL fields.
- API Problem fields: type, title, status, code, detail, requestId; stable safe text only.

## Task0: reproducible foundation and API contract

**Files:** root Gradle/settings/catalog/properties/wrapper/verification metadata/locks; core, application, verification and training-target build files; web/package.json/lock/config; compose.yaml; docs/API.md; .gitignore/.gitattributes; docs/DELIVERY.md.

**Produces:** project-scoped build/run commands, locked dependencies and exact API examples. No placeholder application is called a finished product.

- [ ] Verify local Java21, Node24 and Docker Desktop. Record versions and immutable image digests; verify wrapper bytes from official sources. Do not run NVM or alter global configuration.
- [ ] Initialize an authentic local main repository in Slotkeeper only. Set local author metadata; ignore runtime credentials, caches, artifacts, screenshots-in-progress and container state.
- [ ] Configure four Gradle modules, constructor-based boundaries, JUnit, formatting, Javadoc, core coverage and architecture checks. Declare test/runtime configurations before strict dependency metadata generation so cold resolution is covered.
- [ ] Pin npm packages and produce one lockfile. Keep app serving the packaged static frontend; the standalone Vite server is optional development only.
- [ ] Write API.md with request/response/error examples for every design endpoint, CSRF/login encoding, pagination and idempotent replay semantics before frontend/backend work separates.
- [ ] Verify wrapper, dependency resolution, configuration and typed frontend setup; preserve actual command outcomes. Initial foundation checks are configuration checks, not fabricated feature tests.
- [ ] Commit the reviewed foundation and contracts.

## Task1: domain states and pure use cases

**Files:** core/.../identity/Actor.java, workspace/Membership.java, reservation/ReservationStatus.java, Reservation.java, BookingPolicy.java, ReservationService.java, BookingUnitOfWork.java, BookingTransaction.java, BookingQueries.java, RuleViolation.java; matching core tests.

**Consumes:** the fixed design/API contract. **Produces:** the interfaces above and deterministic decisions for explicit inputs/time.

- [ ] Write failing parameterized tests: HELD confirms before expiry; exact expiry boundary rejects; CANCELLED/EXPIRED cannot revive; CONFIRMED cancellation is idempotent; foreign owner is unavailable; a third active hold and fifth successful confirmation fail; canceled confirmations still consume allowance.
- [ ] Run :core:test and observe missing types/behavior before implementation.
- [ ] Implement immutable values, scoped permission policies and use-case orchestration. A transaction port owns atomicity; no Spring/JDBC/HTTP imports in core. Validate exact30minute schedule/horizon/zone semantics.
- [ ] Add tests for DST transition instants, half-open adjacency, disabled rooms and invalid input bounds. Never use the production method itself as an expected-value oracle.
- [ ] Run :core:test, formatting/Javadoc and coverage; commit only after the behavioral checks pass.

## Task2: PostgreSQL transactions, expiry and contention

**Files:** application/.../persistence/JdbcBookingUnitOfWork.java, JdbcBookingTransaction.java, JdbcBookingQueries.java, DatabaseTime.java; reservation/ExpiryWorker.java; db/migration/V001__baseline.sql; application integration-test sources including ContentionWorker.java and ReservationConcurrencyTest.java.

**Consumes:** core transaction/query contracts. **Produces:** real durable reservation, idempotency and event behavior.

- [ ] Write failing PostgreSQL Testcontainers tests for one winner, different-body idempotency conflict, harmless same-key retry, cross-workspace composite-FK rejection, actor quota, late confirmation and stale cancellation after reassignment.
- [ ] Run the dedicated integration-test task and observe the missing adapter/migration behavior.
- [ ] Implement additive schema, constraints, prepared SQL, actor/slot lock ordering and database clock reads after locks. Keep lock/statement timeouts bounded. Persist business effect, original identity, event and idempotency record in one transaction.
- [ ] Implement effective expiry on reads and safe lazy/scheduled cleanup. A cleanup/cancel update can clear only its own matching slot assignment.
- [ ] Add a barrier-controlled100attempt test from two independent Java worker processes against one real database. Workers use the same production reservation path and distinct test actors, bypass only the HTTP rate layer, record process identities/outcomes and assert exactly one active allocation. Use argument files/environment rather than long Windows command lines or credential arguments.
- [ ] Test actual PostgreSQL lock waits crossing expiry, idempotency insert contention, rollback after injected failure and restarted reads. Preserve all outcomes including retryable errors.
- [ ] Run integration checks and architecture/coverage checks; commit.

## Task3: authentication, scoped HTTP and sessions

**Files:** application/.../security/SecurityConfiguration.java, ActorResolver.java, ApiAuthenticationHandlers.java, RequestBoundsFilter.java, ProblemMapper.java; identity/JdbcIdentityRepository.java; workspace/WorkspaceController.java; reservation/ReservationController.java; session migrations and HTTP tests.

**Consumes:** core actor/use cases and JDBC adapters. **Produces:** real authenticated API and shared JDBC sessions.

- [ ] Write failing real-HTTP tests for anonymous401, login session rotation, missing/invalid CSRF403, scoped foreign404, member/admin403, forbidden-field400 and logout invalidation.
- [ ] Implement Spring form-login JSON success/failure handlers, Argon2id at the specified settings, bounded hash concurrency, HttpOnly/SameSite local cookies and JDBC sessions. No custom bearer-token protocol or client-owned tenant authority.
- [ ] Resolve a fresh membership for scoped operations. Keep core authorization effective when use cases are invoked without a controller. Reject duplicate/unknown JSON fields and expose only typed DTOs.
- [ ] Implement actual login/CSRF refresh/logout, bounded REST errors and security headers. Default API errors never redirect silently to HTML.
- [ ] Start a second app process against the same test database; verify session use, current-authority checks and logout invalidation across both. Preserve separate evidence from pure domain tests.
- [ ] Run real-HTTP and integration checks; commit.

## Task4: resource controls, admin and activity

**Files:** application/.../security/JdbcRateLimiter.java, RateLimitFilter.java, LoginWorkBudget.java; workspace/RoomController.java, JdbcRoomRepository.java; activity/ActivityController.java; additional additive migrations and tests.

**Consumes:** authenticated actor/workspace scope. **Produces:** bounded mutation/login traffic and usable scoped admin functions.

- [ ] Write failing tests for atomic60/minute actor/workspace decisions across processes,20/minute actual-peer login decisions, spoofed-forwarded-address non-bypass,16KiB rejection, bounded pagination/date ranges and dependency failure.
- [ ] Implement short transactional rate windows with retention/cleanup; finish the rate transaction before booking locks. Return429 with Retry-After; dependency failure cannot turn protection off.
- [ ] Add prepared/scoped room create/update/list and append-only successful booking activity. Never grant a member admin authority through submitted fields. Disabling a room does not cancel existing reservations.
- [ ] Pin tests for daily allowance after cancellation, literal hostile-looking input, safe errors/logs and runtime-event privileges. Failed foreign access cannot leak target content through an error or audit response.
- [ ] Run required checks; commit.

## Task5: complete product interface

**Files:** web/src/api/client.ts, contracts.ts; session/SessionProvider.tsx; workspaces/WorkspacePicker.tsx; reservations/AvailabilityView.tsx, ReservationSummary.tsx, MyBookings.tsx; admin/RoomsView.tsx, ActivityView.tsx; App.tsx and design tokens; behavioral tests and e2e.

**Consumes:** API.md exact fields and statuses. **Produces:** the normal member/admin experience, independent of rehearsal internals.

- [ ] Write failing behavior tests for login/CSRF refresh, workspace-change state clearing, slot conflict refresh, hold expiration and member/admin navigation.
- [ ] Implement the approved calm operations layout, accessible controls and mobile order. Do not add cyber/neon decoration or expose implementation internals in booking copy.
- [ ] Keep auth/session state server-owned. Handle errors by machine code; hold countdown uses serverNow/expiresAt with a monotonic client offset and never replaces server validation. Render user input as text.
- [ ] Run type/unit checks against the exact API and packaged app. Playwright must complete member booking/cancel and admin room/activity journeys, tenant separation, conflict and mobile flows.
- [ ] Capture one bounded desktop/mobile QA round, fix observed issues in one batch, confirm only if necessary. Screenshots must come from the actual running final app.
- [ ] Commit the working interface.

## Task6: independent rehearsal and vulnerable positive control

**Files:** verification/.../RehearsalMain.java, OwnedTarget.java, ActorSession.java, ScenarioRunner.java, EvidenceReport.java, SanitizedObservation.java; training-target/.../TrainingTargetMain.java; runner/export tests; scripts and rehearsal documentation.

**Consumes:** frozen HTTP/API contract and the design's A01–A12 table. **Produces:** observed evidence, not a scanner or production security certification.

- [ ] Write failing exporter/admission tests: foreign target marker refused, credentials/redacted fields refused, missing dependency becomes INCOMPLETE, unexpected response becomes FAIL/ERROR, no partial run is labeled complete, HTML text is escaped.
- [ ] Implement owned-target checks and real authenticated HTTP sessions with cookies/CSRF kept in memory. Only local script-owned target URIs/instance markers are accepted.
- [ ] Implement A01–A12 with independently specified response and final-state assertions. Reuse expiry setup for stale-action checks; preserve configured TTL and separate rate-blocked requests from DB-admitted contention.
- [ ] Implement the isolated synthetic missing-scope fixture and demonstrate the A02 leak. Keep its reduced identity model explicit. Ensure it cannot load product sessions/database and is absent from the app artifact/default Compose runtime.
- [ ] Generate JSON, Markdown, escaped standalone HTML and SHA256SUMS. Include exact revision/configuration, scenario IDs, request IDs, statuses, state assertions, timestamps and outcome counts; exclude all credentials/raw session material.
- [ ] Run a complete fresh local rehearsal. Keep failed histories; dependent cases do not pass by omission. Commit runner and fixture source after checks.

## Task7: one-command delivery, review and local reference release

**Files:** slotkeeper.ps1, slotkeeper.sh, compose.yaml, Dockerfile, .github/workflows/verify.yml, README.md, docs/ARCHITECTURE.md, THREAT_MODEL.md, API.md, DELIVERY.md, decisions/, docs/images/, LICENSE, NOTICE and applicable third-party notices.

**Consumes:** the complete working product and observed evidence. **Produces:** the local GitHub-ready reference release.

- [ ] Package app+frontend as one non-root application image, use an isolated owned PostgreSQL volume and separate migration/runtime roles; resolve and record image digests.
- [ ] Implement up/verify/rehearse/down in PowerShell and Bash with local Docker-context/ownership checks. Do not issue prune, global stop or unscoped destructive cleanup.
- [ ] Prepare pinned check-only CI matching local commands. Explicitly distinguish prepared CI from a remotely observed run. Exclude private runtime paths, credentials, build caches and bulk Docker data from source/evidence.
- [ ] Run the complete clean locked verification and fresh Compose rehearsal, including two-process/session and100attempt evidence. Re-run checks only for fixes/new concerns after that.
- [ ] Obtain a focused code review of authorization, transactions/expiry, resource controls and evidence admission; fix material findings with regression checks.
- [ ] Finish English documentation with actual commands, actual screenshots, actual results and honest limits. Record exact versions, source revision and commands in DELIVERY.md. Include a5minute reviewer walkthrough.
- [ ] Inspect/stage only intended public source, make authentic local commits and a local v0.1.0 tag after verification. Check clean status and fresh-start reproducibility. Do not claim public GitHub/live deployment in this local scope.
- [ ] Mark GOAL.md complete only when every acceptance check actually passed; report remaining scope honestly if the deadline arrives first.

## Self-review and execution note

The design-to-task mapping covers product, auth, scoping, state/expiry, durable concurrency, quotas, limits, UI, abuse rehearsal, positive control, reports, packaging and documentation. Review Focus items are pinned to Tasks2/3/4/6. All consumers use the shared API/domain contracts above. External SSO, payments, notifications, internet-facing operation and PQC are explicitly future work.

The design received a bounded read-only builder check on18November2026: no important unanswered implementation question was found; one-day scope remains ambitious. User authorization is to finish today's reference goal, so implementation proceeds through these bounded milestones using the specified local scope and existing constraints.
