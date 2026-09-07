# TCC correctness review and repair plan

Reviewed baseline: `516d9ff5411c32e1ebca44d8b3209b2127b7d7b4` (main). Scope: all six modules, persistence migrations, HTTP contract, recovery, failure injection, deployment and test harness. This is a learning lab; no external transaction manager or workflow engine is introduced.

## Findings and planned repairs

| Priority | Finding and failure scenario | Repair |
|---|---|---|
| P1 | `CoordinatorService.runTryPhase` skips FAILED rows. Crash after `markParticipant(FAILED)` but before CANCELLING leaves TRYING; recovery can confirm other participants and strand the failed branch. | Atomically record failure and decision; handle legacy FAILED snapshots by cancelling. Require all three registered participants to be TRIED before Confirm. |
| P1 | `CoordinatorTxOps.transitionGlobal` blindly overwrites state. Request, scheduled recovery, and manual recovery may concurrently drive the same transaction. Short row locks do not protect the RPC interval; stale results can reverse decisions and participant outcomes. | Database-time expiring ownership token, checked on every persisted operation; renew before RPC; reject stale results; enforce a one-way transition graph. |
| P1 | 409 handling commits FAILED separately from HEURISTIC. A crash between the two leaves an unrecoverable CONFIRMING transaction because FAILED is skipped. | Commit participant conflict and HEURISTIC together; detect contradictory legacy phase snapshots. |
| P1 | Participant `lockById` cannot lock an absent row. Concurrent Try/Cancel can insert or merge over one another. Catching `DataIntegrityViolationException` inside the transaction cannot safely recover a PostgreSQL transaction and may miss deferred flush failures entirely. | Acquire a PostgreSQL transaction advisory lock for txId before the first read, in all three phase methods; retain txId PK and tombstones; remove duplicate-insert catches. |
| P1 | `Product.version` and `Account.version` are absent from V1 schemas while ddl-auto=validate is enabled. Services fail startup. | Add V2 migrations; do not modify applied V1 migrations. |
| P1 | `RecoveryService` is conditional but `AdminController` requires it even when recovery.enabled=false. Both coordinator integration test contexts cannot start. | Keep manual recovery bean; condition only the scheduler bean. |
| P2 | HTTP client discards response bodies and accepts a wrong transaction/state as success. | Require matching txId and phase-appropriate state before recording success. |
| P2 | Money/identifier validation does not match SQL precision/length; coordinator silently rounds amounts. | Validate scale, precision, and identifier lengths at the HTTP boundary; use exact scale conversion. |
| P2 | Recovery scans are unbounded; failed phase retries do not touch the global timestamp. | Bounded oldest-first scan, lease exclusion, and per-drive timestamp/attempt update. |
| P2 | Confirm mocks target the unused one-argument overload; service tests share mutable fixtures; recent-recovery test asserts only >=0 after sleeping. | Correct overloads, reset fixtures between cases, use deterministic SQL timestamps and substantive assertions. |
| P2 | E2E startup is published before it succeeds; `mvn test` relies on previously packaged service jars. No CI enforces real integration execution. | Publish singleton only after startup, package before E2E and require non-skipped suites in CI. |

## Execution order and acceptance criteria

1. Repair coordinator durability and ownership. Check failed-Try restart, conflict restart, invalid transitions, terminal no-op, active lease exclusion and stale-owner writes.
2. Repair participant serialization and schema. Check parallel duplicate Try, concurrent preventive Cancel/Try, parallel finalization and exactly-once resource accounting.
3. Repair HTTP validation, recovery scheduling and test harness. Check matching protocol replies, disabled scheduling with manual recovery, bounded scans and isolated fixtures.
4. Build all modules; run PostgreSQL-backed service tests and Compose E2E explicitly. Report failures/skips, never treat unavailable Docker as a successful integration run.

## Follow-up limitations

- POST /api/orders has no client idempotency key: a caller retry after a lost response creates a different txId and may place another order. Adding a key requires a documented API contract, request fingerprint and retention policy; participant txId idempotency does not solve this.
- Lab endpoints, seeded passwords and failure injection are deliberately exposed for exercises. This change does not add authentication or make the lab deployable as a banking service. Failure injection should be disabled and endpoints isolated outside local exercises.
- HEURISTIC still needs human reconciliation; this change never automatically retries it. Tombstone/log retention must account for arbitrarily delayed messages before any cleanup is introduced.
- Bounded fixed-delay recovery is not exponential backoff, a transaction deadline or an operator work queue. Persistent unavailable participants remain pending for recovery.
- Lease takeover permits duplicate in-flight RPCs. Safety depends on participant serialization/idempotency and immutable decisions; a lease does not cancel an HTTP request already sent.
- These changes assume all coordinator instances run the repaired protocol. Stop old instances before upgrade; an old driver does not respect ownership tokens.

## Implementation notes and references

Participant row creation uses `save`, which may invoke JPA merge for assigned identifiers; it is not an insert-only guard. All phase entry points now acquire the same transaction-scoped advisory lock before checking existence. Hash collisions can serialize unrelated transactions, but do not weaken exclusion. PostgreSQL releases these locks on transaction completion. See [1] [PostgreSQL explicit locking](https://www.postgresql.org/docs/16/explicit-locking.html) and [2] [Spring Data JPA entity persistence](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html).

New lease settings: `tcc.recovery.lease-ms=30000`, `tcc.recovery.batch-size=100`. The lease renews at each coordinator database operation and before each RPC. A timed-out or expired driver cannot record an outcome after ownership changes. Lease duration should exceed expected HTTP timeouts plus DB latency; expiry remains safe but can cause extra replay. Manual recovery stays available when `tcc.recovery.enabled=false`; only automatic scheduling is disabled.

The V2 migrations add resource version columns and coordinator lease columns. Open Session in View is disabled so independent coordinator transactions do not leave request-bound stale entity snapshots. Resource finalization also takes product/account write locks, reducing optimistic-lock conflicts across different txIds sharing a resource.

## Verification record

Initial repair commit `998c92eccadba37e1819cf955906c313af0b34ad` passed GitHub Actions [run 34091147236](https://github.com/ankhanh-vni/tcc-distributed-transaction-lab/actions/runs/34091147236): all modules packaged; 77 tests passed, zero failures/errors/skips; all 11 required PostgreSQL/Compose integration suites ran. The subsequent revision adds explicit database-commit rollback and bounded recovery scan checks. Its result is tracked by the PR checks.

Local verification parsed all Maven XML and YAML, passed `git diff --check`, and confirmed that the report gate rejects missing integration reports. Java/Maven/Docker execution occurred on GitHub's runner because this workspace lacks those working runtimes. This is functional and concurrent regression coverage, not a load test, exhaustive fault-injection proof, dependency vulnerability audit, or production security certification.
