# Slotkeeper v1 design

**Date:** 18 November 2026.
**Purpose:** a usable room-reservation application whose abuse resistance is demonstrated through reproducible local evidence.
**Delivery target:** the local reference release defined in GOAL.md, today by 23:59 Europe/Budapest.

## 1. Product and approach

The owner selected Slotkeeper: a booking product with general application-security exercises. This design chooses meeting rooms with exclusive, fixed 30-minute slots so that state, contention and abuse defenses can be made precise within one day. Two fictitious coworking workspaces have independently scoped members, admins, rooms and bookings.

Three approaches were considered:

1. A challenge-only vulnerable website would support exercises but provide little ordinary product behavior.
2. A microservice platform with an external identity provider, broker and cache would increase integration work without being necessary for the selected invariants.
3. A modular monolith with a separate teaching fixture provides a coherent product, inspectable transaction boundaries and a useful abuse rehearsal. This is the implementation choice for today's scope.

The implementation choices below are defaults selected for this deadline, not claims that the owner individually specified each detail. The main application is always hardened. The independent teaching fixture is a separate process/artifact with synthetic data.

## 2. Stack and boundaries

- Java 21; Gradle 9.2.0 wrapper with the published distribution SHA-256.
- Spring Boot 4.1.2, Spring Security and Spring Session JDBC using the Boot-managed dependency set.
- PostgreSQL 18.7, Spring JDBC and additive Flyway migrations. Actual image availability/digests are verified and recorded before dependency freeze; unavailable artifact metadata is a real blocker, not a reason to invent a pin.
- React 19.3.1, TypeScript 5.9.3, Vite 8.3.8, Node 24 and Playwright 1.65.1. Resolve companion packages once and commit the exact npm lockfile.
- No broker/cache is needed for this release: the database owns reservations, shared sessions and atomic rate-limit state.
- English code, comments, UI, API, tests, reports and documentation.

Gradle modules:

- core: framework-free immutable domain values, policies, results, ports and reservation use cases.
- application: Spring wiring, HTTP/security, JDBC adapters, migrations and packaged React resources, organized by feature.
- verification: independent HTTP/database assertions, process ownership, scenario orchestration and evidence export; no reuse of production authorization decisions as expected results.
- training-target: a small, explicitly vulnerable local authorization fixture. It is not a runtime dependency of application and has no access to product sessions or database volumes.
- web/: React source built into the normal application; not an additional production server.

Default runtime: application and PostgreSQL. A verification profile starts a second application process against the same owned database to verify persistence and shared sessions. Host publications use IPv4 loopback, ports 4190 and 4192. The teaching fixture uses 4193 only when explicitly launched by the rehearsal.

## 3. Journeys and interface

Member flow: log in, select an authorized workspace, select a room and day, inspect available slots, create a hold, see its authoritative expiration, confirm it, then inspect/cancel a personal booking. A stale selection produces an inline conflict and refreshes availability.

Admin flow: use the same workspace boundary, create/update a room's name/description/enabled state, and inspect workspace activity. Existing reservations survive disabling a room; new holds on disabled rooms are rejected. Disabling does not silently cancel other users' bookings.

Use a calm operations interface: ink/navy text, pale neutral surfaces, restrained blue accent, one clear primary action, readable typography and visible focus. No neon/cyber styling. Workspace and time zone stay visible. Desktop has a room rail, availability panel and reservation summary; mobile follows the same reading order without sideways page scrolling. Loading, empty, conflict, expiry, rate-limit, unauthenticated and forbidden states have concrete copy.

Security evidence is a separate generated local report. Normal booking screens explain booking decisions, not cryptographic or test-harness internals.

## 4. Identity and authorization

Use Spring Security form login and opaque server sessions stored in PostgreSQL. Do not create a custom JWT/signature protocol. A session identifies an immutable user UUID; workspace membership and its MEMBER or WORKSPACE_ADMIN role are read from the database for the requested operation rather than trusted from body fields or cached tenant claims.

Seed four clearly synthetic demo identities, two per workspace, with an admin and a member in each. Local demo credentials are explicitly identified as synthetic and unsuitable for external deployment. Account registration, password reset, MFA and external SSO are future scopes.

- Argon2id via the maintained Spring/Bouncy Castle implementation: salt 16 bytes, hash 32 bytes, memory 19,456 KiB, iterations 2, parallelism 1.
- Bound login fields and request body before password hashing; cap simultaneous password-hash work at four and rate-limit unauthenticated attempts by actual peer address.
- Session fixation protection on login; invalidate server state and cookie on logout; 30-minute idle timeout.
- Cookie name SLOTKEEPER_SESSION, HttpOnly, SameSite=Lax, Path=/. Secure is false only for the documented local HTTP mode; a future TLS deployment requires Secure=true.
- Same-origin API with no permissive CORS. Use framework CSRF tokens for login, logout and every state-changing browser request. Read a fresh token after authentication changes; no authentication token in localStorage.
- Workspace paths select a scope, not authority. A foreign workspace/object returns 404; an authorized member attempting an admin operation returns 403. Anonymous API requests return 401.
- Owner/user/workspace/role/state fields are never bindable from reservation input. Unknown JSON fields and duplicate keys are rejected.

## 5. Reservation contract

Time is stored as UTC instants. Workspace time zones are explicit; seed Europe/Budapest and render all slot labels with that zone/offset. Generate selectable instants rather than parsing ambiguous local wall times. A slot is [start, start + 30 minutes), capacity one. Booking horizon is the next seven days; each demo room has a finite day schedule. No arbitrary-duration or multi-room reservation in v1.

Reservation states:

- HELD: owns a slot until its authoritative expiresAt.
- CONFIRMED: owns the slot until cancellation; it no longer expires as a hold.
- CANCELLED and EXPIRED: terminal and cannot be reconfirmed.

Transitions: HELD -> CONFIRMED, CANCELLED or EXPIRED; CONFIRMED -> CANCELLED. Repeating an owner's cancellation of CANCELLED is harmless. Expired holds cannot be revived. A stale cancellation must never release another reservation that subsequently obtained the slot.

Default hold TTL is 120 seconds. At most two unexpired holds per actor/workspace are permitted. At most four successful confirmations per actor/workspace/slot-local-date are permitted; cancellation does not restore that daily allowance. These constants are included in configuration and the report's observed policy snapshot.

Immutable identity, original creation/confirmation timestamps and transition history remain inspectable. A retry can read current reservation state; it does not fabricate an unchanged historical state.

## 6. Database and transaction invariants

Tables include users, workspaces, workspace_memberships, rooms, slots, reservations, actor_booking_guards, idempotency_records, rate_buckets, booking_events and Spring Session's JDBC tables. UUIDs identify public entities. Composite foreign keys include workspace identity so that an accidentally mis-scoped relationship cannot connect different workspaces.

A slot is unique by workspace/room/start and has at most one assigned reservation ID. A reservation can have a historical slot while no longer owning its current assignment.

Mutation order:

1. Authenticate, validate basic input shape and resolve current scoped authority.
2. Apply a bounded rate decision in its own short database transaction.
3. Start the booking transaction and obtain any idempotency-row lock.
4. Lock the actor/workspace guard, then the target slot, then any reservation rows, always in that order. Sweepers never acquire actor guards after slot locks.
5. Read authoritative PostgreSQL clock_timestamp() after the relevant locks; do not reuse an HTTP-start timestamp or transaction_timestamp() from before a lock wait.
6. Reconcile an expired assignment, enforce ownership/state/room/quota rules, then commit the reservation, slot assignment, idempotency result and successful booking event atomically.

Use real row locks/conditional updates and constraints, not Java synchronized or a JVM-local mutex as the capacity guarantee. Lock/statement waits are bounded; contention failures have a documented retryable 503 RESOURCE_BUSY response and do not leave partial effects.

Availability treats an expired HELD assignment as available even before a periodic cleanup persists EXPIRED. Reads return the effective business state at the observed database time. Any competing mutation rechecks under lock. A bounded expiry job persists terminal state; it can release only the matching assigned reservation ID.

Database migration ownership and runtime access are separated in Compose. Session/rate writes and booking events are scoped to the app schema. Runtime access cannot update/delete historical booking-event rows. Database administrators and host/Docker administrators remain trusted; this is not tamper-proof logging.

## 7. Idempotency, limits and errors

Creating a hold and confirming it require an Idempotency-Key of 8–128 allowlisted ASCII characters. Scope is workspace, authenticated actor, operation and key. Compute the fingerprint from the validated typed request with a documented stable field order. The same scoped key and request returns the original resource identity and current authorized state without another effect; the same key with different input returns 409 IDEMPOTENCY_CONFLICT.

Current authentication/authority and request limits run before an idempotency result can be returned. New quota checks do not reject a harmless existing-key replay merely because the first request filled the quota. Concurrent key insertion uses a database uniqueness constraint and bounded re-read/lock, not a check-then-insert race.

Default mutation bucket: 60 requests per minute per actor/workspace, atomic in PostgreSQL. Login bucket: 20 attempts per minute per peer address. Ignore untrusted forwarded-address headers. Record the exact chosen window semantics; do not claim a fixed-window limit is a bot detector. Expired rate/idempotency records have bounded cleanup and retention; replay safety does not disappear solely because a short HTTP timeout passed.

JSON body limit 16 KiB; login values have independent length bounds; page size 1–50; date range at most seven days; names/descriptions have explicit bounded lengths. Multipart/URL import, payments, messages and uploads are outside today's surface.

Use stable RFC9457-style error JSON with a machine code, safe user message and request ID. Main outcomes: 400 malformed/disallowed input, 401 login required, 403 role/CSRF denial, 404 unavailable scoped object, 409 state/quota/idempotency conflict, 413 body limit, 429 rate limit with Retry-After, 503 dependency/contention failure. No stack trace, SQL, password, session token or raw input in public errors.

## 8. API surface

- GET /api/csrf; POST /api/session; GET /api/session; POST /api/logout.
- GET /api/workspaces.
- GET /api/workspaces/{workspaceId}/rooms; POST /rooms; PATCH /rooms/{roomId} for scoped admins.
- GET /api/workspaces/{workspaceId}/availability?roomId=...&date=YYYY-MM-DD.
- POST /api/workspaces/{workspaceId}/holds with roomId and startAt.
- POST /api/workspaces/{workspaceId}/reservations/{id}/confirm.
- POST /api/workspaces/{workspaceId}/reservations/{id}/cancel.
- GET /api/workspaces/{workspaceId}/reservations/{id}; GET /api/workspaces/{workspaceId}/my-bookings.
- GET /api/workspaces/{workspaceId}/activity for admins, with bounded pagination.
- GET /actuator/health exposes minimal readiness only. A local rehearsal identity/readiness marker identifies the owned instance without exposing secrets or granting access.

All room and reservation paths are expanded under the same workspace prefix. Typed request/response examples and exact errors are frozen in docs/API.md before adapters and frontend diverge.

## 9. Rehearsal and teaching fixture

Twelve mandatory hardened-target scenarios:

| ID | Attempt | Required observation |
| --- | --- | --- |
| A01 | Anonymous booking access/mutation | 401 and no booking effect |
| A02 | Foreign-workspace reservation lookup | 404, no foreign object/fields returned |
| A03 | Another member's cancellation | 404 and unchanged target reservation |
| A04 | Member admin action or forged authority fields | 403 for role denial; 400 for forbidden input; no escalation |
| A05 | Missing/invalid CSRF token | 403 and no mutation |
| A06 | Same idempotent operation concurrently/retried | One resource/effect, same identity across successful retries |
| A07 | Same key with changed typed input | 409 and unchanged original effect |
| A08 | Confirmation after hold expiration | 409; no confirmed reservation |
| A09 | Stale cancellation after a slot is reassigned | New owner's assignment remains intact |
| A10 | Hoarding beyond two unexpired holds | Additional distinct hold refused; existing holds remain correct |
| A11 | Mutation flood and forged forwarded address | Observed 429 without bypass; bounded writes and retry guidance |
| A12 | 100 competing last-slot attempts | Capacity invariant holds; all outcomes classified, no hidden failures |

A12 has separate layers: full HTTP guards may legitimately return 429/409. An independent database-focused test makes 100 competing attempts from independent JVM/application processes without relying on rate rejection, proving the database admission path is exercised. No report equates a request blocked by rate limiting with one that reached a reservation lock. Use a TTL/time budget that prevents legitimate expiration from changing the question during contention.

Additional required tests cover the daily confirmation allowance, cancellation/retry ordering, session rotation/logout across processes, login throttling, oversized/malformed input, prepared SQL values, literal rendering of hostile-looking room names, dependency failure and DST date boundaries.

The training target demonstrates A02 with a deliberately missing object-scope check. It uses only fixed synthetic records and a fixture-controlled synthetic actor; its identity model is explicitly not the product login. Its response demonstrates the leak, while the real app's equivalent request does not. It is a positive control for the scenario, not a claim of identical complete application behavior or comparative performance.

Runner targets are started/owned by its scripts, bound to loopback and checked with an instance marker. No arbitrary scan URL. Cookies, passwords and CSRF tokens stay in memory/private runtime state, never in exported reports. Each case retains sanitized request metadata, observed status/code, final state assertions and PASS/FAIL/ERROR/INCOMPLETE. A failed prerequisite marks dependent cases incomplete, not passing. Export JSON, Markdown, standalone escaped HTML and SHA256SUMS; preserve failed run directories.

## 10. Quality, operations and evidence

One-command Windows and Bash entry points: up, verify, rehearse and down. Use local Docker Desktop/daemon only, a unique Slotkeeper Compose project and labelled volumes. Refuse remote Docker contexts; never prune or stop unrelated resources. Down is scoped and keeps persistent data unless a separately explicit owned-run cleanup is requested.

Tests: domain/state/property examples; PostgreSQL Testcontainers and true process contention; real authenticated HTTP/CSRF checks; React behavior tests; Playwright member/admin/mobile/conflict journeys. Core coverage gate: at least 90% line and 85% branch. Architecture tests assert core purity and the absence of training-target from app runtime. No tests whose only purpose is reproducing a getter or the implementation's own expected value.

Run formatting, Java compilation/Javadoc warnings checks, dependency locks/strict verification, frontend type checks and the complete local verification command. Capture actual versions, commands, code revision and outcomes. Prepared GitHub CI uses pinned actions and matching checks; local success is not described as a remotely observed CI run.

Deliver README with actual desktop/mobile images and a five-minute walkthrough; docs/ARCHITECTURE.md, THREAT_MODEL.md, API.md, DELIVERY.md, a short decision log, Apache-2.0/NOTICE and dependency notices. Keep authentic local commits. A code review covers identity, scoping, transactions, expiry and evidence admission before declaring completion.

## 11. Limits and next versions

This is a local reference application with seeded accounts and synthetic data. The current identity, object, concurrency and resource controls are tested under their documented conditions. It does not certify the whole system, resist a malicious host/database administrator or establish production-scale capacity.

Future candidates: external OIDC/passkeys, notifications with cost budgets, single-use booking credits, flexible/multi-resource scheduling, a richer training curriculum, managed deployment, and integration with the existing PQC signer. They are not silently added to today's definition of done.

## 12. Primary references consulted

- Spring Boot requirements: https://docs.spring.io/spring-boot/system-requirements.html
- Spring Session JDBC: https://docs.spring.io/spring-session/reference/guides/boot-jdbc.html
- PostgreSQL explicit locking: https://www.postgresql.org/docs/18/explicit-locking.html
- OWASP password storage: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- OWASP session management: https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
- OWASP API resource consumption: https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/
- OWASP sensitive business flows: https://owasp.org/API-Security/editions/2023/en/0xa6-unrestricted-access-to-sensitive-business-flows/

Consulted on 18 November 2026. Version metadata was checked directly against Maven Central and the npm registry; runtime image digests remain to be resolved during foundation work.
