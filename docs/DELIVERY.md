# Local delivery record

**Target release:** v0.1.0, 18 November 2026.
**Status:** release candidate; the final clean, revision-matched rehearsal is pending.

The deliverable is an English room-reservation product and an inspectable local security reference. Public GitHub publication, an internet-facing deployment and portfolio publication are outside this delivery. This record distinguishes development checks from the final release gate.

## Observed development checks

| Check | Observed result before the final rehearsal |
| --- | --- |
| Framework-free domain | 40 tests; line and branch coverage gates passed |
| Application unit and architecture checks | 5 tests passed |
| Actual PostgreSQL and HTTP integration | 36 tests passed, including eight durable-clock checks and repeated lock-wait expiry checks |
| Rehearsal/exporter and teaching fixture | 36 tests passed, including bounded HTTP work and failure accounting |
| React behavior | 18 tests passed |
| Actual packaged-app browser journeys | 8/8 passed: member, room admin, stale-slot conflict and per-workspace roles, each on desktop and mobile |
| Mocked PowerShell launcher boundaries | 14/14 passed without real Docker commands |
| Packaged product and distribution builds | Passed on Java 21 and Node 24 |

The final release section will record the fresh locked command, exact source revision, observed test totals and actual rehearsal directory. Earlier successes do not substitute for that gate.

## Meaningful faults found and corrected

- A foreign workspace could allocate a rate bucket before authorization. Admission now resolves current scoped membership first; a regression exercises the rejected request and unchanged bucket inventory.
- A cold authentication provider can call password encoding to build a dummy hash. Both encoding and matching now share the four-job per-process Argon2 budget; the regression uses the actual cold provider.
- Transaction creation and transaction-deadline failures originally reached an incorrect generic error path. Bounded dependency failures now produce safe service-unavailable problems rather than exposing infrastructure details.
- An expiry worker acquired slot and room-related locks in the opposite order from booking. It now locks the room first and skips busy rows; the competing-room regression first reproduced the timeout and then passed.
- Real browser runs exposed backward database wall-clock observations, including a transition observed 2.250273 seconds before its reservation creation. The exact host-clock cause was not established. A durable, nondecreasing booking timeline now preserves the domain guard without pretending that a regressed wall clock measures physical elapsed time. [ADR 0005](decisions/0005-durable-booking-time.md) explains the implementation and limits.
- The initial internal-only Docker bridge did not forward the configured loopback ports on the observed Docker Desktop host. Owned containers/networks were recreated with separate ordinary bridges while retaining the database volume. The final design makes no outbound-network sandbox claim.
- Launcher review found unlabeled exact-name resource collisions, incomplete startup cleanup and inherited Compose interpolation values. Ownership checks, cleanup scope and controlled child-process values now cover those cases; mocked regressions preserve the original failure when cleanup also fails.

## First complete rehearsal attempt

At source revision `b9ad653f9abcbcfafcdac1d68ac2168c4b69e6b3`, the clean locked build passed: 115 Java tests, 18 React tests, documentation, architecture, coverage and formatting checks. The actual 100-attempt database exercise recorded two distinct JVM process IDs, one created reservation, 99 slot conflicts and one active assignment in 4.693 seconds.

The real HTTP rehearsal ran from `2026-11-18T13:12:16.946558900Z` to `2026-11-18T13:14:22.265114800Z`: **14 PASS, 0 FAIL, 0 INCOMPLETE, 1 ERROR**. A11 received 48 responses and 82 transport errors; it did not pass. All other cases, including real expiry, stale cancellation, teaching positive control, shared sessions and independent database invariants/permissions passed. The failed run is retained locally under `artifacts/rehearsal/20261118T131216758Z/`.

A11 had released 130 HTTP operations together against a product configured for at most 100 connections, after the separate contention clients had already used keep-alive connections. The revised rate-policy exercise keeps all 130 attempts while bounding in-flight operations at eight. The explicit budget and all failed attempts remain visible in reports. A12 retains its separate 100-operation HTTP start barrier and independent two-JVM database proof. No server rate, connection or quota limit was raised to make the test pass.

The new regression checks that a gated batch fills but never exceeds its eight-operation budget, submits all 130 indices exactly once and retains two injected failures. A separate configuration change makes PostgreSQL/process integration tests always execute rather than restoring observations from Gradle's task cache. The final release still requires a fresh, complete rehearsal and a clean-start environment.

## Environment and reproducibility

Observed host: Windows, PowerShell 7, Docker Desktop 29.8.1, local Linux Docker engine, Oracle JDK 21 and Node.js 24.14.0 / npm 11.9.0. Java 21 is selected process-locally; the machine default Java and global Docker configuration are unchanged. No NVM scripts are used.

The runtime image is Eclipse Temurin 21.0.13. Gradle 9.2.0, Spring Boot 4.1.2, PostgreSQL 18.7 and React 19.3.1 are pinned. Strict Gradle dependency verification, lockfiles, an npm lockfile and immutable container digests accompany the source. The official Gradle wrapper JAR SHA-256 is `423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9`.

Windows PowerShell is the observed runtime path. The Bash launcher delegates to the same PowerShell entry point on Windows; native Linux execution and the prepared GitHub Actions workflow are not claimed as observed remote runs.

## Release verification procedure

Commit the intended source changes, then run from the repository root:

```powershell
pwsh -NoProfile -File ./slotkeeper.ps1 rehearse
```

The command rejects a dirty source tree for revision-matched rehearsal. It runs strict clean Java/PostgreSQL/frontend verification, launcher regressions and formatting checks; obtains fresh two-JVM contention evidence; builds the product; starts the owned second app and teaching target; exports every required scenario; and stops those two secondary services even on failure. The normal product and persistent database remain available.

The separate browser command is:

```powershell
cd web
npx --no-install playwright test
```

Its eight observed journeys were run against the packaged local product. They consume real synthetic fixture allowances and create retained booking/room history; they are not stateless requests.

## Screenshots and evidence boundaries

`docs/images/slotkeeper-desktop.png` and `slotkeeper-mobile.png` are actual Chromium captures of Riley holding a Studio North room on the running application. The holds were released after capture. They are not generated mockups; credentials, cookies and traces were not exported.

The rehearsal report and report screenshot will be attached only after a complete actual run. Reports record observations, not a security certification. The independent two-process database exercise runs below HTTP admission; rate-limited HTTP requests cannot replace its contention evidence. Report hashes detect changed bytes and do not attest to authorship or host integrity.

## Local state and explicit limits

The new repository is separate from the private messaging-service checkout. Existing sites, other repositories and unrelated Docker workloads were preserved. Launcher shutdown retains Slotkeeper's own persistent database volume and does not prune shared Docker state.

The public fixture password is deliberately local-only. There is no real-user onboarding, TLS deployment, OIDC, payment, notification, backup/HA or production throughput claim. The teaching fixture is an independent artifact on a separate network, with no product database credentials, shared volume or normal application dependency.
