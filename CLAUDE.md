# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A **learning lab** that re-implements Try-Confirm-Cancel (TCC) distributed transactions from scratch on Java 21 / Spring Boot 3 / PostgreSQL, deliberately mimicking Oracle MicroTx's TCS internals. **XA, JTA, MicroTx, Kafka, and workflow engines are banned on purpose** — do not suggest them as solutions, even when they would be the "real" answer. The point is to make the failure modes visible.

`README.md` and `docs/concepts.md` carry the architectural narrative; do not paraphrase them in code comments or new docs.

## Build & run

```bash
mvn -DskipTests package          # build all jars (required before docker compose, which mounts target/)
docker compose up --build -d     # postgres + 4 services on :8080/:8081/:8082/:8083
```

`scripts/init-databases.sh` runs once on first Postgres start and creates **one DB+user per service** (`inventory_db`, `payment_db`, `order_db`, `coordinator_db`). Cross-DB queries are intentionally impossible.

## Tests

```bash
mvn test                                   # all modules; Docker-dependent tests auto-skip if Docker is unavailable
mvn -pl coordinator-service test           # single module
mvn -pl e2e-tests -Dtest=HappyPathTest test # single test class
mvn -pl e2e-tests -Dtest=IdempotencyTest#duplicateConfirm test  # single test method
```

E2E tests and slice tests are gated by `@EnabledIf("dockerAvailable")` (see `e2e-tests/.../TccSystem.java` and equivalent in each service) — they will silently skip without Docker, so a green `mvn test` does **not** mean the integration paths ran. If you change protocol behavior, run e2e tests explicitly.

There is no configured linter or formatter.

## Load-bearing invariants (do not violate)

These are the rules the system actually depends on. Several span multiple files and are easy to break without realising.

### 1. Log first, then RPC (coordinator)
`CoordinatorService.runTryPhase`/`runConfirmPhase`/`runCancelPhase` always call `CoordinatorTxOps.transitionGlobal` / `markParticipant*` **before** the participant HTTP call. If a crash happens between, the recovery loop reads the persisted intent and re-drives. **Never** flip this order — there is no other recovery story.

### 2. Participants are idempotent by `tx_id` PK
Every participant table (`inventory_reservation`, `payment_freeze`, etc.) uses `tx_id` as the primary key. Every phase takes a transaction-scoped PostgreSQL advisory lock before reading the txId row, including when that row is absent. Keep the lock through commit. Do not catch duplicate-insert errors inside an already failed transaction; JPA save may also merge assigned-id entities. Don't add surrogate keys, and don't assume "this can't be a duplicate."

### 3. Preventive cancel writes a tombstone
A `cancel(txId)` that finds **no row** must `INSERT` a `CANCELLED` row, not no-op. This closes the race where a slow Try arrives after the coordinator has already given up. The late Try then sees the tombstone and rejects with 409. See `InventoryTccService.cancel` lines around the `existing.isEmpty()` branch — keep this shape in any new participant.

### 4. HEURISTIC is terminal and never auto-retried
A 409 from a participant during Confirm or Cancel (Confirm-after-Cancel / Cancel-after-Confirm) → coordinator transitions to `GlobalTxState.HEURISTIC`. The recovery loop's `findStuck` query intentionally excludes HEURISTIC. Do not add it back. Heuristic outcomes need human reconciliation; silent retries make things worse.

### 5. Confirm phase is one-way
`runConfirmPhase` does **not** fall back to Cancel on RPC failure. Non-409 failures leave the global tx in `CONFIRMING` for the recovery loop to retry. The only escapes from CONFIRMING are CONFIRMED or HEURISTIC. (Try-phase failures *do* transition to CANCELLING — that asymmetry is correct.)

### 6. `CoordinatorService` ↔ `CoordinatorTxOps` split is intentional
Spring's `@Transactional` is proxy-based and does not apply to self-invocation. The transactional unit-of-work methods live in `CoordinatorTxOps` precisely so `CoordinatorService` can call them through the proxy. Don't merge the two beans.

## Protocol shape

REST verbs encode TCC phase uniformly across every participant:

| Phase   | HTTP method | Endpoint shape                              |
|---------|-------------|---------------------------------------------|
| Try     | `POST`      | `/tcc/<resource>` (body carries `txId`)     |
| Confirm | `PUT`       | `/tcc/<resource>/{txId}/confirm`            |
| Cancel  | `DELETE`    | `/tcc/<resource>/{txId}`                    |
| Status  | `GET`       | `/tcc/<resource>/{txId}`                    |

`tx_id` is propagated in path/body and via `X-Tx-Id` header. New participants must follow this shape — `ParticipantClient` builds URLs from it.

## Failure injection (test-only)

Per-participant: `X-Fail-At: TRY|CONFIRM|CANCEL` and `X-Sleep-Millis: N`. The coordinator forwards `X-Inject-Fail-At-Participant` → `X-Fail-At` and `X-Inject-Confirm-Sleep-Millis` → `X-Sleep-Millis`. Wired by `tcc-common`'s `FailureInjectionFilter` + auto-config; any new service that includes `tcc-common` gets this for free.

## Recovery tuning

`TccProperties.Recovery` exposes:
- `tcc.recovery.enabled` (default true) — `RecoveryScheduler` is `@ConditionalOnProperty` on this; manual `RecoveryService` remains available; tests disable it to drive recovery manually via `recovery.recoverOnce()`.
- `tcc.recovery.fixed-delay-ms` (5000) — scheduler period.
- `tcc.recovery.stuck-after-ms` (8000) — minimum age before a non-terminal tx is considered stuck.

Tests that need deterministic recovery typically set `tcc.recovery.enabled=false` and call `recoverOnce()` directly.

## Module map

- `tcc-common` — shared DTOs, `TccException`/`TccErrorCode`, `FailureInjectionFilter`, header constants. Depended on by every service.
- `inventory-service` (`:8081`), `payment-service` (`:8082`), `order-service` (`:8083`) — TCC participants. Each follows the `*TccController` → `*TccService` → JPA entity pattern; copy `inventory-service` when adding a new participant.
- `coordinator-service` (`:8080`) — global tx state machine, participant HTTP client, `@Scheduled` recovery loop, admin endpoint.
- `e2e-tests` — Docker-Compose-backed integration tests; numbered scenarios documented in README.

