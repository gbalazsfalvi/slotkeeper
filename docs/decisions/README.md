# Decision notes

These notes explain the selected v1 design. **Accepted** describes a design decision; it does not mean its integrated verification has passed. Observed results belong in [DELIVERY.md](../DELIVERY.md).

| Decision | Topic |
| --- | --- |
| [0001](0001-modular-monolith.md) | A modular monolith with a framework-free domain |
| [0002](0002-postgresql-authority.md) | PostgreSQL owns capacity, expiry time and durable retries |
| [0003](0003-server-sessions-and-current-scope.md) | Shared server sessions with current scoped authority |
| [0004](0004-independent-evidence-and-teaching.md) | Independent observed evidence and a separate teaching artifact |
| [0005](0005-durable-booking-time.md) | Durable nondecreasing booking time under clock regression |
