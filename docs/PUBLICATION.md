# Slotkeeper publication record

## Published reference

On 18 November 2026 the owner authorized the public [gbalazsfalvi/slotkeeper repository](https://github.com/gbalazsfalvi/slotkeeper) and [v0.1.0 release](https://github.com/gbalazsfalvi/slotkeeper/releases/tag/v0.1.0). The default branch is `main`; the licence is Apache-2.0. The annotated release tag identifies `9f9075abf301420c702868e035425fc683795d3c`. Its executable source is the locally tested `44a4caff44eb14424957ce51ae21bbc153fcf25f`; the release commit adds reviewed documentation, images and evidence. Those local observations remain dated and frozen.

The release assets contain three actual captures, the portable evidence ZIP and a checksum inventory. The ZIP has 14 explicitly selected public files: original report formats and database observations, screenshot captures, licence material and an inspection guide. Its SHA-256 is `55a21177bddc7fbd0527a5f5a2be5e437ccb017cf1534cf7f7c4141023149577`. A download from the published GitHub release was checked against that digest. Private runtime configuration, credentials and complete runtime-folder dumps were excluded.

## Portfolio

The static case study is live in [English](https://balazsfalvi.hu/work/slotkeeper/) and [Hungarian](https://balazsfalvi.hu/hu/munkak/slotkeeper/). It links to source, release and downloadable evidence and shows the actual desktop product, mobile booking journey and complete rehearsal report. The reference index groups this independent project separately from professional work. It is absent from the homepage's professional-work highlights.

The portfolio deployment verified all 171 generated files against their complete manifest over public HTTPS, following 24 server-side critical-route checks. Existing portfolio content and previous PQC/Fuzzy evidence downloads were preserved. The portfolio is a static presentation; Slotkeeper's application, database and deliberately vulnerable teaching fixture remain local.

## Build correction and verification boundaries

Fresh hosted builds exposed missing dependency-resolution metadata that the original warm Windows cache had not requested. The [dependency verification record](DEPENDENCY_VERIFICATION.md) documents the exact corrections and primary artifact sources. Those metadata corrections preserve strict checksum enforcement, pinned dependency versions, the public tag and frozen release evidence. Use current `main` for a cold build and the outage-handling follow-up described below.

The release also includes a separate `slotkeeper-v0.1.0-cold-build-metadata.patch` and companion checksum inventory for readers starting from the original tagged source. The patch applies to its exact metadata and adds only the 30 reviewed POM/module hashes. Its SHA-256 is `fcf93e2b28c918a3b27419bb27c40fd2308fa93a0eee3e9f2871f8cd6d150b55`. It leaves application source and the original release assets unchanged.

The [GitHub Actions workflow](https://github.com/gbalazsfalvi/slotkeeper/actions/workflows/verify.yml) records its own hosted status for each revision. It checks the wrapper, locked Java/PostgreSQL/process/frontend verification, mocked launcher regressions and frontend formatting. Browser journeys, the complete launcher rehearsal and their original screenshots are separate local observations. A hosted check is not substituted for that local evidence, and a failed hosted attempt is not a passing observation.

[Hosted run 44328251657](https://github.com/gbalazsfalvi/slotkeeper/actions/runs/44328251657), at `6b4a5248cf962d8b7bbd6a7dedd4f4cafaed990d`, completed successfully on Ubuntu 24.04 with Temurin 21.0.13 and Node 24.14. It exercised all workflow gates. A separate Windows verification refreshed all 36 real PostgreSQL/HTTP integration tests and 18 React behavior tests successfully; the other Java task results were cached or up to date and are not claimed as fresh executions.

The preceding hosted run `44327148180` passed dependency resolution and 35 of 36 integration tests, but failed the database-outage HTTP-status assertion. The next run passed without a booking-code change. The failure recurred in `44329082642` with a safe500 response instead of503. Investigation [run 44330656650](https://github.com/gbalazsfalvi/slotkeeper/actions/runs/44330656650) then captured the bounded cause chain: `TransactionSystemException` wrapping `SQLException`, without a reported SQLState. No exception message or SQL statement was included in that diagnostic.

A focused local regression reproduced the500 with that JDBC transaction-completion wrapper. Current `main` classifies the wrapper as SERVICE_UNAVAILABLE503 and retains INTERNAL_ERROR500 for non-JDBC transaction failures. Tests check both boundaries and ensure exception messages and throwable payloads do not enter diagnostics. Unknown-failure diagnostics contain at most eight exception types and allowlisted SQLState codes. This follow-up changes the current source; the original v0.1.0 snapshot, observed counts and images remain historical evidence. Hosted status for the updated revision is recorded by the linked workflow.

Transaction completion can fail after an indeterminate write outcome. The [HTTP contract](API.md) therefore requires retaining the original idempotency key when retrying booking writes. A503 is not a claim that no business effect committed. [Spring's transaction exception contract](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/transaction/TransactionSystemException.html) describes the commit/rollback wrapper observed here.

## PQC direction

The owner selected issuer-backed signed hold permits for a future milestone. The [research note](research/2026-11-18-pqc-admission-options.md) and [detailed proposed contract](superpowers/specs/2026-11-18-slotkeeper-pqc-admission-design.md) cover a separate issuer, replay-safe redemption, admission limits and comparative measurements. A fresh builder review identified no important unanswered planning question. The detailed design remains pending owner approval and is not a v0.1.0 feature or a claim of measured abuse reduction.
