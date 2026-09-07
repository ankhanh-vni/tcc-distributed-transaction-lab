# Sandbox stress test and performance changes

## Scope and approval boundary

The user authorized sandbox stress testing and performance improvements, but requires review before architectural changes. This change preserves services, HTTP protocol, participant order, transaction boundaries, durable decisions, mutation locking and recovery ownership. It adds no queue, cache, parallel participant execution, or new infrastructure dependency.

## Reproduction

The `stress` CI job packages a fixed baseline (`bcfde6e4fa165c544fe5abca1d7a2012d539494d`) and the candidate. It runs three paired repetitions in A/B, B/A, A/B order on the same disposable runner. Each repetition creates a fresh Docker project and service databases, runs the same 80-request warm-up and fixed-count load, then deletes the containers and volumes. Ports are loopback-only, PostgreSQL is not published, and the harness cannot accept a remote target URL. Run `python3 scripts/stress-test.py --source <packaged-checkout> --label <name>` to reproduce a single run.

Each Java service has a 128–256 MiB heap and a 512 MiB container memory limit. PostgreSQL has a 512 MiB container limit. `com.tcc` logging is WARN for both revisions, to isolate protocol/persistence costs. Images, settings, fixtures, connection pools and timeouts are the same for both revisions. The runner's CPU and memory description is recorded with every summary.

The load model is closed-loop, no think time, with 1, 8, 32 and 64 workers. Cases cover independent resource keys, one hot resource, completed idempotent replay, key conflicts, and injected Confirm response delays. SQL statement counts include PostgreSQL-recorded application statements; startup, fixtures and warm-up are excluded from per-case counters. Latency percentiles describe HTTP responses, not a universal transaction-completion SLO; timeout cases separately report time until durable convergence. Short replay/conflict cases are especially sensitive to thread startup and runner variation.

## Baseline findings

[Successful baseline run](https://github.com/ankhanh-vni/tcc-distributed-transaction-lab/actions/runs/34136236274): 1,800 measured HTTP requests, 1,338 confirmed transactions including warm-up and preseeded replay keys, no accounting mismatch. At 64 concurrent independent orders, throughput was 45.98 requests/s and p95 was 1,897.65 ms. New orders required approximately 89 coordinator SQL statements each. Completed-key replay required seven statements, with unnecessary serialization even though the mapping and terminal outcome cannot change.

The first sandbox attempt did not reach readiness on an internal Docker network. The load harness was corrected to use a dedicated bridge with loopback-only bindings. That failed attempt generated no performance measurements and is excluded.

## Implemented optimizations

1. Coordinator participant bookkeeping uses guarded updates without first loading each participant entity. These updates remain inside the existing REQUIRES_NEW operation, after locking the global row and checking driver ownership. SQL predicates preserve txId ownership and the allowed prior participant state. Failure evidence and the global decision still commit or roll back together. This removes twelve participant SELECTs per successful order, as well as unnecessary full-row ORM updates.
2. Existing idempotency mappings are read before taking the creation lock; a missing mapping is checked again under the original lock before insertion. Full key and fingerprint comparisons are preserved. Mappings have no deletion or mutation API and no TTL.
3. That lookup also reads the global state. An already-terminal replay returns its txId without acquiring a driver lease. In-flight work still goes through the original guarded driver. This relies on the existing invariant that terminal states never transition. No cache is involved.

The lookup adds one read for a newly created key, so the expected net saving for a successful new order is eleven coordinator SQL statements. The paired benchmark verifies the actual count rather than relying on that estimate.

## Correctness gates

Each run checks HTTP codes, stable txId per key, eventual confirmation after injected timeouts, exact agreement of confirmed txIds across all four databases, one coordinator key/global record and three participant records per order, and per-resource stock/balance accounting with no remaining reservations/freezes. Failure is a CI error, not a latency sample silently discarded. Existing regression tests are retained, including failed-decision commit rollback. New tests verify that completed replay does not wait for creation/driver locks and that guarded direct updates cannot change the wrong transaction or overwrite CANCELLED with TRIED.

CI publishes raw response timings, SQL call/elapsed-time breakdowns, container snapshots and paired summaries as `stress-results`. The PR records the final measured comparison and tested commit.

## Architecture proposals requiring review

None is implemented in this change. If substantially greater new-order throughput is needed after these local optimizations, a separate design review should consider whether to reduce coordinator commit frequency or execute independent participant calls concurrently. Both alter crash windows and scheduling behavior; they need a revised failure analysis and explicit approval before code changes. The present measurements do not establish either proposal's benefit.

## Limits

This is a bounded stress experiment on one 4-vCPU-class CI runner, not a sustained open-loop arrival-rate test, long-duration soak, multi-coordinator failover benchmark, or production capacity claim. Fresh databases do not cover years of retained idempotency/log rows. Per-case p95 is summarized across repetitions, not pooled into a misleading global percentile. Improvements must be assessed together with run-to-run ranges, SQL work and correctness outcomes.
