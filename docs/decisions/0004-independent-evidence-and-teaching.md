# 0004 · Separate product protection from teaching and evidence

**Status:** accepted for v1.
**Date:** 18 November 2026.

## Context

A security demonstration can appear successful when the request never reached its target, the fixture did not exercise a flaw, a prerequisite failed or expected results reused the product's own authorization code. Public reports also risk exposing private runtime material.

## Decision

Keep the product controls always enabled. Run the deliberately missing-scope positive control as a separate fixed-record teaching artifact with a reduced synthetic actor model and no product database/session access. Launch it only for explicit owned local rehearsal.

Use independent HTTP and database observations for the frozen case inventory. Admit only fixed loopback origins with expected instance markers and launcher ownership checks. Preserve `PASS`, `FAIL`, `ERROR` and `INCOMPLETE`; missing prerequisites never pass. Separate HTTP rate outcomes from the two-JVM PostgreSQL contention result.

Export typed allowlisted observations as JSON, Markdown and escaped standalone HTML with SHA-256 inventories into fresh run directories. Never export runtime-folder dumps or credentials.

## Consequences

Reviewers can inspect both a real positive control and the hardened comparison without placing vulnerable code in the product. Failed runs remain useful evidence.

The fixture is not the full product identity model. Instance markers and hashes prevent specific accidents or detect byte changes; they do not attest to a hostile host or certify application security. Final integrated observations are still required.
