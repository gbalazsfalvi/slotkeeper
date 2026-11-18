# Slotkeeper v1 — delivery goal

**Target:** 18 November 2026, 23:59 Europe/Budapest (22:59 UTC).
**Current state:** design and implementation planning; no implementation or test success is claimed by this document.

Deliver a complete local room-reservation product and an inspectable application-security reference today. The deadline is a planning target; completion requires the checks below, not merely the passage of time.

## Product

Two synthetic workspaces, workspace-scoped member/admin permissions, server-side login sessions, room availability, temporary holds, confirmation, cancellation, personal bookings and an administrative activity view. The normal booking journey works in a polished responsive English React interface.

## Security and correctness

The backend owns identity, workspace access, object ownership, state transitions, expiry and quotas. PostgreSQL transactions and constraints preserve the last-slot invariant across independent processes. Retried operations do not create duplicate reservations. Foreign objects, forged owner/role fields, CSRF, hoarding, request floods and stale actions have explicit outcomes.

## Demonstration and evidence

A one-command local rehearsal runs twelve named abuse scenarios against owned synthetic targets, checks actual HTTP responses and database effects, includes an isolated vulnerable positive control, and exports sanitized JSON/HTML/Markdown plus SHA-256 inventories. A 100-attempt contention test and two-process/session tests use real PostgreSQL. Real desktop/mobile screenshots accompany the README.

## Definition of done

- [ ] One-command local Compose launch and documented shutdown preserve unrelated resources.
- [ ] A member can log in, select a workspace/room/time, hold, confirm, inspect and cancel a booking; an admin can manage rooms and view scoped activity.
- [ ] Core unit tests, PostgreSQL integration/concurrency tests, HTTP security tests and browser journeys pass.
- [ ] The twelve rehearsal cases have complete observed assertions; no expected negative result is confused with a harness failure.
- [ ] An isolated vulnerable fixture demonstrates at least one real missing-authorization control; it is absent from the normal app artifact/runtime.
- [ ] Another application process can share sessions and stored reservations without weakening object scope, quotas or capacity.
- [ ] Clean locked builds, formatting, Java documentation, architecture and coverage checks pass.
- [ ] README, architecture, threat model, API contract, decision notes, screenshots, actual reports, limitations, licence/notices and a prepared CI workflow are ready for GitHub.
- [ ] A final local rehearsal from a fresh Slotkeeper-owned run directory succeeds, and the working tree/commits match the delivered release.

The local reference is the deliverable. Production deployment, full identity-provider integration, real payments, external email/SMS, broad internet bot detection and a PQC integration are follow-up scopes. The report makes no security-certification, universal attack-coverage or production-scale performance claim.

## Working schedule

These are allocation targets in Europe/Budapest, not reported completion times.

| Target window | Deliverable |
| --- | --- |
| 11:15–12:00 | Reviewed design, frozen contracts, toolchain and quality foundation |
| 12:00–14:00 | Domain, PostgreSQL migrations and concurrency correctness |
| 14:00–15:30 | Authentication, sessions, scoped API and request controls |
| 15:30–17:30 | Booking and admin interface |
| 17:30–19:30 | Abuse rehearsal and isolated positive control |
| 19:30–21:00 | Compose/two-process rehearsal, browser checks, screenshots |
| 21:00–22:30 | Review fixes, documentation, local release verification |
| 22:30–23:59 | Reserved integration/debugging buffer |
