# Slotkeeper

A local room-reservation application and an inspectable application-security reference. Book a room, hold a time, confirm it, and manage your bookings. Then inspect a separate rehearsal that exercises workspace isolation, ownership, retries, expiry, quotas and competing requests against synthetic data.

**Release status:** pending final integrated verification. Development checks are recorded in [DELIVERY.md](docs/DELIVERY.md); the complete local release, final rehearsal and screenshots are not yet claimed.

## Try it locally

Prerequisites: Git, a Java 21 JDK, Node.js 24.14 or later in the 24.x line, and a local Docker daemon with Docker Compose. Windows uses PowerShell 7.4 or newer; native Linux uses Bash, Python 3 and `flock`. Select Java 21 in the invoking shell. Dependencies are pinned in the Gradle catalogs, lockfiles, verification metadata and npm lockfile.

Run commands from the repository root. The release rehearsal requires committed source changes so its evidence identifies the tested revision.

PowerShell:

```powershell
.\slotkeeper.ps1 up
.\slotkeeper.ps1 status
```

Bash:

```bash
./slotkeeper.sh up
./slotkeeper.sh status
```

Open [Slotkeeper on loopback](http://127.0.0.1:4190). The normal application serves both the interface and its API. The optional Vite development server is separate from this packaged experience.

| Account | Workspace access |
| --- | --- |
| `alex` | Member, Harbor Works |
| `morgan` | Admin, Harbor Works |
| `riley` | Member, Studio North |
| `jordan` | Admin, Studio North; member, Harbor Works |

All four synthetic accounts use the public fixture password **`Slotkeeper-Local-Only!`**. Selecting an account on the sign-in screen fills these local credentials. No real tenants or external accounts belong in this demo.

Follow the [five-minute walkthrough](docs/WALKTHROUGH.md) for member booking, room administration and evidence review.

## What to inspect

- **Product behavior:** seven days of availability, fixed 30-minute room slots, temporary holds, confirmation, cancellation, personal bookings and scoped room/activity administration.
- **Server decisions:** current database membership, object ownership and typed request fields determine authority. Browser navigation and submitted role/owner fields cannot grant access.
- **Durable correctness:** PostgreSQL transactions coordinate slot assignment, actor allowances, retry receipts and successful booking events across application processes.
- **Separate evidence:** twelve named abuse cases, an isolated vulnerable teaching fixture and a shared-session check have explicit observed outcomes. Missing prerequisites remain visible.

Holds last at most 120 seconds on the server booking timeline and never extend beyond the slot start. The timeline cannot move backward; it can pause during a wall-clock regression, as documented in [ADR 0005](docs/decisions/0005-durable-booking-time.md). Each actor/workspace may have two unexpired holds and four successful confirmations per slot-local date; cancellation does not refund the confirmation allowance. A workspace has at most 20 rooms, including disabled rooms. Workspace times are explicit; the server validates expiry and state.

## Commands and local ports

Use the same verb with either entry point:

| Verb | Purpose |
| --- | --- |
| `up` | Build/start the owned local product and database |
| `status` | Inspect the owned local environment |
| `verify` | Run locked project verification checks |
| `rehearse` | Obtain fresh verification evidence, explicitly start extra rehearsal targets and export observed evidence |
| `down` | Stop the owned environment while retaining its persistent data |

For example, run `.\slotkeeper.ps1 verify` and `.\slotkeeper.ps1 rehearse` in PowerShell, or `./slotkeeper.sh verify` and `./slotkeeper.sh rehearse` in Bash. Finish with the corresponding `down` command. The scripts are scoped to Slotkeeper resources; they do not publish a site or repository.

| Loopback port | Role |
| --- | --- |
| `4190` | Normal product UI/API |
| `4192` | Second product process, explicit rehearsal only |
| `4193` | Isolated teaching target, explicit rehearsal only |
| `55419` | Owned PostgreSQL instance |
| `4195` | Optional Vite development server |

## Evidence and screenshots

The rehearsal exports `report.json`, `report.md`, standalone `report.html` and `SHA256SUMS` into `artifacts/rehearsal/<timestamp>/`. Separate `database-contention.json` evidence records the two-JVM database exercise. The final command output and [delivery record](docs/DELIVERY.md) identify the actual run directory and source revision. A report is complete only when every required check is observed and passes; `FAIL`, `ERROR` and `INCOMPLETE` are preserved.

Actual desktop and mobile captures of the packaged local product:

![Slotkeeper desktop booking: room, time grid and temporary hold](docs/images/slotkeeper-desktop.png)

<details>
<summary>Mobile booking screen</summary>

<img src="docs/images/slotkeeper-mobile.png" alt="Slotkeeper mobile booking flow" width="390" />

</details>

The final revision-matched report and its screenshot are pending the complete rehearsal.

HTTP rate rejections and database lock admissions answer different questions. The 100-attempt database exercise must retain its independent two-process evidence; a set of HTTP `429` responses cannot establish last-slot transaction correctness. SHA-256 inventories detect changed report bytes; they do not certify their origin or prove security.

## Technical map and limits

Java 21, Gradle 9.2.0, Spring Boot 4.1.2, PostgreSQL 18.7 and React 19.3.1 form a modular monolith. The frontend is packaged into the application. The framework-free domain lives in `core`; HTTP/security/JDBC adapters live in `application`; `verification` and `training-target` are independent artifacts.

Read the [architecture](docs/ARCHITECTURE.md), [HTTP contract](docs/API.md), [threat model](docs/THREAT_MODEL.md) and [decision notes](docs/decisions/README.md). [GOAL.md](GOAL.md) defines the acceptance gate.

This reference uses local HTTP, public synthetic credentials and owned loopback resources. It is not a production deployment or a penetration-test certification. External identity, internet-facing operation, payments, notifications and PQC integration are outside v1. A prepared CI workflow is not evidence of a remote CI run.

Licensed under [Apache-2.0](LICENSE); see [NOTICE](NOTICE).
