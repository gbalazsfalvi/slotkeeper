# Slotkeeper operating rules

Slotkeeper is an independent local reference project. All source, comments, UI, reports and documentation are English.

- Keep application work in this project and never import private employer source. The owner explicitly authorized publication to `gbalazsfalvi/slotkeeper` and a separate bilingual case study on the existing personal portfolio. That authorization does not extend to unrelated projects, Docker workloads or machine-wide configuration.
- Use Java 21, constructor injection, immutable domain values and explicit application boundaries. Core has no Spring, JDBC, HTTP or JSON dependency.
- Use feature packages under io.github.gbalazsfalvi.slotkeeper. Keep classes focused. Public Java APIs have meaningful Javadoc; inline comments explain non-obvious decisions rather than repeat statements.
- Start behavioral changes with meaningful failing tests. Use actual PostgreSQL for locking, transactions, constraints, sessions and contention tests; do not substitute H2 or a mocked repository for database evidence.
- Never edit a committed Flyway migration. Add the next migration. Bind all SQL values and scope object access by workspace and authenticated actor.
- The product always enforces its controls. Deliberately vulnerable code belongs only to the separate training-target artifact, excluded from the application dependency graph and normal runtime.
- Never log passwords, cookies, CSRF tokens, authorization headers or raw login/request bodies. Reports use allowlisted synthetic observations, not runtime-folder dumps.
- Preserve exact observed results. FAIL, ERROR and INCOMPLETE are real outcomes; missing observations cannot become PASS. Concurrency, functional correctness and latency observations have different scopes.
- Use local Docker Desktop only, project-scoped Compose resources and loopback host ports. No prune, unrelated stop/down, remote Docker context or volume-wide cleanup.
- Keep Gradle/npm dependencies pinned and locked; keep strict Gradle verification. Do not relax a check to make a deadline.
- Keep authentic Git commits on main and preserve published release tags and frozen evidence. Remote publication is a separate authorized workflow; local launch scripts must never publish. The proposed PQC admission design remains unimplemented pending detailed approval.
- NVM is available only from CMD/PowerShell; do not run NVM scripts.

Read GOAL.md, the design and implementation plan before implementation. Record actual milestone checks in docs/DELIVERY.md as work progresses.
