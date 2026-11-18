# 0003 · Use server sessions and current workspace scope

**Status:** accepted for v1.
**Date:** 18 November 2026.

## Context

A user may be an admin in one workspace and a member in another. Stored identity, selected workspace and old retry receipts must not turn a previous permission into continuing authority. The local release does not need a custom token protocol or an external identity service.

## Decision

Use Spring Security form login, Argon2id and opaque sessions stored by Spring Session JDBC. Rotate the session identity on login and invalidate it on logout. Use framework CSRF tokens and a same-origin browser client; refresh CSRF after login. Keep authentication material out of browser localStorage and public evidence.

Read account availability and current database membership for scoped operations. Derive actor identity from the server session. Accept only operation-specific writable fields. Return an unavailable scoped object as `404`; deny an authorized member's admin action with `403`. A stored retry result does not bypass authority or traffic admission.

## Consequences

Two app processes share session/logout state, and workspace roles remain independently scoped. Database access is required for current authority; the design intentionally avoids stale role claims in the browser.

The public fixture password and local HTTP cookie settings are local-only choices. External identity, recovery, MFA and a TLS deployment need separate provisioning and review.
