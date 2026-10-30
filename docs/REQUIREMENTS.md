# Requirements checklist

Implementation follows the supplied PDF's Parts 1–3. Part 4 (open questions) is
intentionally excluded as requested.

- [ ] Validate each HR record independently; reject invalid data without echoing
  personal values.
- [ ] Enforce tenant/source/employee/version idempotency, fingerprint conflicts,
  stale-event handling and ownership rules transactionally.
- [ ] Use the shared sync engine for both real-time events and snapshot rows.
- [ ] Stream snapshots into tenant-scoped staging; protect invalid rows and
  employees created after the snapshot cutoff.
- [ ] Pause risky deactivation at the configurable safety threshold and provide
  audited operator approval/rejection.
- [ ] Write an outbox record atomically with every applied employee change;
  publish with retry and consume idempotently.
- [ ] Cancel future confirmed bookings and release seats exactly once on exit.
- [ ] Enforce stateless authentication, role checks and tenant isolation.
- [ ] Expose metrics, structured PII-free logs, bounded cache and safe
  problem-style error responses.
- [ ] Provide unit, integration, concurrency, failure-injection and benchmark
  coverage with a rule-to-test map.
- [ ] Start PostgreSQL, RabbitMQ and the application through Docker Compose.
- [ ] Report benchmark results only after measurement; retain explicit
  placeholders before then.
