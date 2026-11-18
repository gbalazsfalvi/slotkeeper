# Local delivery record

**Target release:** v0.1.0, 18 November 2026.
**Status:** local v0.1.0 complete. The clean-checkout rehearsal, normal launch and browser verification passed.

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

The final release section below records the fresh locked command, exact source revision, observed test totals and actual rehearsal directory. Earlier checks are retained as development history.

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

The new regression checks that a gated batch fills but never exceeds its eight-operation budget, submits all 130 indices exactly once and retains two injected failures. A separate configuration change makes PostgreSQL/process integration tests always execute rather than restoring observations from Gradle's task cache. The resulting release gate required a fresh, complete rehearsal and a clean-start environment; its outcome is recorded below.

## Environment and reproducibility

Observed host: Windows, PowerShell 7.6.9, Docker Engine 29.8.1 through Docker Desktop, Docker Compose 5.5.1, Oracle JDK 21.0.7+8-LTS-245 and Node.js 24.14.0 / npm 11.9.0. Java 21 is selected process-locally; the machine default Java and global Docker configuration are unchanged. No NVM scripts are used.

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

The [complete report snapshot](evidence/v0.1.0/README.md) and `docs/images/slotkeeper-report.png` come from the successful actual run recorded below. Reports record observations, not a security certification. The independent two-process database exercise runs below HTTP admission; rate-limited HTTP requests cannot replace its contention evidence. Report hashes detect changed bytes and do not attest to authorship or host integrity.

## Local state and explicit limits

The new repository is separate from the private messaging-service checkout. Existing sites, other repositories and unrelated Docker workloads were preserved. Launcher shutdown retains Slotkeeper's own persistent database volume and does not prune shared Docker state.

The public fixture password is deliberately local-only. There is no real-user onboarding, TLS deployment, OIDC, payment, notification, backup/HA or production throughput claim. The teaching fixture is an independent artifact on a separate network, with no product database credentials, shared volume or normal application dependency.

## Final local v0.1.0 observation

**Tested executable-code revision:** `44a4caff44eb14424957ce51ae21bbc153fcf25f`. The later release commit changes only documentation, images, evidence and completion metadata; the report retains the code revision actually exercised.

A fresh local Git clone began without a private runtime configuration, product containers or a database volume. The single PowerShell `rehearse` command generated local ownership/credentials, completed the strict build in **4 minutes 17 seconds**, initialized the empty PostgreSQL database, applied V001 and V002, seeded synthetic accounts and started both apps plus the separate teaching process. The normal `up` command subsequently succeeded from the canonical checkout using that verified local environment. Earlier development data was retained separately.

| Observed check | Result |
| --- | --- |
| Java tests | 117 passed; 0 failures, errors or skips |
| Actual PostgreSQL/HTTP subset | 36 passed; always executed, not restored from a task cache |
| React behavior | 18 passed |
| Packaged desktop/mobile browser | 8 passed in 24.1 seconds, retries disabled |
| Mocked launcher boundaries | 14 passed; no real Docker invocation by that harness |
| Formatting, Javadoc, architecture, locked resolution and core coverage | Passed |
| Complete real rehearsal | 15 PASS; 0 FAIL, ERROR or INCOMPLETE |

Core coverage was **302/319 lines (94.67%)** and **131/140 branches (93.57%)**. Unchanged unit-test outputs may use Gradle's build cache; actual PostgreSQL/process integration observations always execute.

The rehearsal ran from `2026-11-18T13:25:45.965690Z` to `2026-11-18T13:27:49.582507600Z` and exported to `artifacts/rehearsal/20261118T132545704Z/` in the clean checkout. [Original exports and the complete snapshot inventory](evidence/v0.1.0/README.md) are included for inspection. The raw independent contention artifact matches the same source revision, records two distinct JVM PIDs, all 100 attempts, one creation, 99 slot conflicts and one active assignment; its recorded duration is 5,360 milliseconds.

A11 received all 130 responses with at most eight operations in flight: **73 rate rejections, zero transport failures**. A12's separate HTTP burst recorded **1 creation, 50 slot conflicts, 38 quota rejections and 11 rate rejections**. These classifications remain distinct from the database proof. S02's seven invariant-violation counters were all zero; runtime event-update, event-delete and schema-create permissions were false; the transaction was read-only.

The second app and teaching process were stopped automatically after rehearsal. Actual desktop/mobile captures were taken against this verified environment and their temporary holds released. The standalone report was rendered from its hash-checked original HTML. The normal product image includes the packaged React assets and excludes teaching/verifier classes and artifacts. Exact generated runtime passwords were checked against tracked public source and evidence without exporting them.

Focused review of authority, transactions/clock behavior, resource controls, launcher ownership and evidence handling found no remaining Important or Critical findings after the documented fixes. This is a bounded review of the reference implementation, not a certification. The prepared workflow and native Linux launch are not described as observed remote runs.

The local release uses an authentic Git history and a local `v0.1.0` tag. No public repository or site was published in this delivery.
