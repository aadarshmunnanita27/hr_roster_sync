# HR Roster Sync

A Java 21 / Spring Boot service for safe, multi-tenant synchronization of
versioned HR events and nightly employee snapshots. It uses PostgreSQL as the
source of truth and RabbitMQ for downstream employee events.

The implementation follows Parts 1–3 of the supplied build prompt. Part 4
(open questions) is intentionally excluded.

## Requirements checklist

- [x] Per-record validation and non-echoing error results for HR events.
- [x] Version ordering, equal-version fingerprint checks and ownership policy.
- [x] Tenant-scoped event processing and employee / office APIs.
- [x] Streaming CSV parsing into bounded batches and PostgreSQL staging.
- [x] Population-reduction safety brake with audited operator decision.
- [x] Shared sync engine for event and snapshot employee updates.
- [x] Transactional outbox with publisher confirms, retries and idempotent
  booking cancellation.
- [x] Stateless JWT role checks and tenant identity from claims.
- [x] Caffeine cache with expiry and invalidation on office/settings writes.
- [x] Prometheus metrics, JSON logs, problem-style errors and synthetic samples.
- [x] Docker Compose, migrations, core tests, benchmark scripts and design note.
- [ ] Run the complete Testcontainers suite and failure-injection tests on a
  host with Docker available.
- [ ] Measure and record the 200,000-row diff time, peak heap and event load
  results on the target machine.
- [ ] Record and publish a demo video and public repository link.

## Run locally

Install Docker Desktop and run from the project root:

```powershell
docker compose up --build
```

Compose starts PostgreSQL, RabbitMQ and the application. Flyway applies schema
migrations at startup. The health endpoint is `http://localhost:8080/actuator/health`;
Prometheus metrics are at `http://localhost:8080/actuator/prometheus`.

The Compose database password and JWT key are local demonstration values, not
production credentials. Set `ROSTER_JWT_SECRET` to a random secret of at least
32 bytes before deploying anywhere other than a local development machine.

## Authentication and example requests

Create short-lived, local HS256 JWTs with the helper. It outputs a token only;
the role, tenant and (for HR sources) source system are claims in the signed
credential.

```powershell
$env:ROSTER_JWT_SECRET = "local-only-change-this-secret-before-deployment"
$env:ROSTER_HR_TOKEN = python scripts\make-token.py --role HR_SOURCE --tenant demo-tenant
$env:ROSTER_ADMIN_TOKEN = python scripts\make-token.py --role TRANSPORT_ADMIN --tenant demo-tenant
$env:ROSTER_OPERATOR_TOKEN = python scripts\make-token.py --role OPERATOR --tenant demo-tenant
```

Create or update an employee (all HR events carry the complete HR-owned state):

```powershell
$event = @{
  employeeId = "DEMO-EMPLOYEE"
  sourceSystem = "demo-hr"
  sourceVersion = 1
  eventType = "CREATE"
  data = @{
    name = "Example Worker"
    email = "worker@example.invalid"
    phone = "+10000000000"
    managerId = "MGR-1"
    officeCode = "BLR01"
    shift = "DAY"
  }
} | ConvertTo-Json -Depth 5

curl.exe -sS -H "Authorization: Bearer $env:ROSTER_HR_TOKEN" `
  -H "Content-Type: application/json" -d $event `
  http://localhost:8080/api/v1/hr/events
```

The event body may contain `tenantId` for compatibility, but a value that does
not match the signed credential is rejected. Prefer omitting it. Event batches
are JSON arrays; each record gets its own result.

Update transport-owned preferences independently:

```powershell
curl.exe -sS -X PUT -H "Authorization: Bearer $env:ROSTER_ADMIN_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"pickupPoint":"Central Station","specialAssistance":false}' `
  http://localhost:8080/api/v1/employees/DEMO-EMPLOYEE/transport-preferences
```

HR owns `name`, `email`, `phone`, `managerId`, `officeCode`, `shift` and
employment status. Transport administrators own `pickupPoint` and
`specialAssistance`. Sending a transport-owned or unknown field in an HR event
rejects that record; existing transport values are never overwritten.

Upload a roster as multipart form data with required fields `sourceSystem`,
`snapshotCutoff` and `file`. Optional `expectedRowCount` and `expectedChecksum`
form the manifest. A manifest mismatch fails the run without applying it.
Operator-only endpoints expose run status/errors and approve or reject runs in
`APPROVAL_REQUIRED`.

## Assumptions and error codes

- `sourceVersion` is a non-negative monotonically increasing integer for each
  employee. Each employee is bound to one authoritative source system; the
  authenticated HR token is also bound to that source. A different source cannot
  write its version sequence. An event or snapshot row is the complete authoritative
  HR-owned state; partial field patches are not supported.
- Equal version and same HR fingerprint is `DUPLICATE`; equal version with a
  different fingerprint is `VERSION_CONFLICT`; lower versions are
  `STALE_IGNORED`.
- A snapshot is streamed in configurable 2,000-row batches. The default
  safety threshold is 30%; a tenant administrator can change it. Missing
  employees include only employees created no later than the snapshot cutoff.
  Invalid rows protect their matching employee from absence-based
  deactivation.
- A complete snapshot waits for operator approval when missing active employees
  meet or exceed the configured threshold. No changes are applied before that
  approval (a configured threshold of zero therefore always requires approval).
  Rejection is audited.
- Cache staleness for office master data and tenant thresholds is bounded by
  the configured five-minute TTL. The instance handling a write invalidates or
  refreshes its local entry immediately. Employee rows used in version checks
  are never cached.
- The default development phone validation accepts 7–15 digits, with optional
  punctuation. Production deployments should configure the tenant's accepted
  phone format as appropriate.

Common error codes:

| Code | Meaning |
|---|---|
| `INVALID_EMAIL`, `INVALID_PHONE`, `INVALID_SHIFT`, `INVALID_NAME` | HR field did not pass boundary validation. |
| `UNKNOWN_OFFICE` | Office code is missing or inactive for the authenticated tenant. |
| `TRANSPORT_FIELD_NOT_ALLOWED` | HR payload attempted to change a transport-owned value. |
| `UNKNOWN_FIELD` | Payload contains an unsupported field. |
| `INVALID_SOURCE_VERSION`, `INVALID_EVENT_TYPE` | Event ordering metadata is invalid. |
| `VERSION_CONFLICT` | Same employee version was received with different HR data. |
| `TENANT_ACCESS_DENIED`, `FORBIDDEN`, `UNAUTHENTICATED` | Tenant or role authorization failed. |
| `INVALID_CSV_HEADER`, `INVALID_MANIFEST`, `UPLOAD_READ_FAILED` | Snapshot upload could not be accepted. |

Error responses contain an error code, generic message, correlation ID and
optional field name. They never include rejected field values, SQL or stack
traces. Logs use JSON and correlation IDs; they do not log HR field values.

## Design and operational notes

See [docs/DESIGN.md](docs/DESIGN.md) for the architecture and sequence diagrams,
failure-mode table, data model, trade-off table and complexity analysis. The
core complexity targets are O(1) indexed event lookup, O(N) streaming snapshot
work, O(batch size) application memory and O(N) staging disk space.

Delivery is at-least-once, not exactly-once: a process can crash after RabbitMQ
acknowledges but before the outbox row is marked published. Consumers dedupe
with `processed_messages`. An unreliable snapshot stops at the safety brake
rather than deactivating employees automatically.

Deliberately left out: Kubernetes, multiple microservices, Redis or external
caches, partial field-level HR updates and a production UI. Observed scale
limits are also intentionally left unclaimed until benchmarked.

### 3 AM runbook

1. Check `outbox_oldest_pending_age_seconds` and `outbox_pending_events`. Growing
   age/depth means downstream delivery is lagging.
2. Check `outbox_publish_failures_total` and
   `outbox_publish_retries_total`; confirm RabbitMQ health and queue depth.
3. Check `hr_events_rejected_total` by `errorCode` and HTTP 4xx/5xx rates for
   source-data or application regressions.
4. Check `reconciliation_safety_brake_total` and the run's aggregate missing
   counts. Do not approve a run until its population drop is understood.
5. Check `booking_cancellations_total` after exit events. The consumer ledger
   and transaction make retries safe.

## Tests and benchmark

Run unit tests with Maven and Java 21:

```powershell
mvn test
```

Testcontainers integration tests require Docker. The machine used for these
instructions does not currently have a running Docker daemon or Java 21, so no
integration or performance results are claimed here. The benchmark section
below is a template and must be filled with actual measurements.

| Hard rule / scenario | Automated coverage |
|---|---|
| Safety threshold and zero-population edge | `SafetyBrakeTest` |
| Canonical HR fingerprint | `HrFingerprintServiceTest` |
| HR / transport field ownership | `FieldOwnershipPolicyTest` |
| Per-record validation and no value echo | `HrEventServiceTest` |
| Cache expiry and explicit invalidation | `CachePolicyTest` |
| Event replay, concurrent ordering, committed outbox publication and duplicate cancellation | `RosterIntegrationTest` (Testcontainers; requires Docker) |
| Tenant authorization matrix, snapshot crash resume, failure injection and planted-PII scan | Not yet covered |

### Benchmark results — run before reporting

| Measurement | Result |
|---|---|
| 200,000-row diff duration (target < 60 s) | Not measured |
| Peak JVM heap during diff | Not measured |
| Event throughput | Not measured |
| Event latency p50 / p99 | Not measured |
| CPU model / cores, RAM, OS | Not measured |
| JVM flags and PostgreSQL version/settings | Not measured |
| Docker CPU / memory resources | Not measured |

Run scripts in [benchmark/README.md](benchmark/README.md). Do not replace
“Not measured” with estimated values.

## Demo and submission

Suggested demo: start Compose; create and update an employee; replay the event
and send an older version; update transport preferences and show HR cannot
overwrite them; upload a complete and a truncated roster; review the brake and
operator decision; deactivate an employee and observe future booking
cancellation; demonstrate pending-outbox recovery; run the 200,000-row
benchmark.

Keep commits small and phase-oriented (schema, core, event API, outbox,
snapshots, security, operations, tests, benchmark, docs). To publish, create a
public repository and use the repository's own remote URL:

```powershell
git init
git add .
git commit -m "Add roster sync project foundation"
git branch -M main
git remote add origin <your-public-repository-url>
git push -u origin main
```

The public repository link and demo recording are user-owned deliverables.
