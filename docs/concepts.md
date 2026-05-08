# Concepts

A primer on what's actually different when you cross a service boundary, and why
TCC looks the way it does.

---

## ACID inside one database

Inside `inventory-service`, `@Transactional` does this:

```
BEGIN
  UPDATE product SET available_qty = available_qty - 1, reserved_qty = reserved_qty + 1 WHERE sku=?
  INSERT INTO inventory_reservation(tx_id, ...) VALUES (...)
COMMIT
```

The DB engine guarantees:

- **A**tomicity — both rows commit or neither does
- **C**onsistency — `CHECK (available_qty >= 0)` is enforced before commit
- **I**solation — `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) makes
  concurrent Tries on the same product line up
- **D**urability — once committed, a crash loses nothing

This works because there's a single transaction manager (the Postgres backend) and
a single resource. Spring's `@Transactional` is a thin wrapper.

## What breaks across services

Now we want `reserve inventory` AND `freeze payment` AND `create order` to all
commit, or all fail. The naive translation is a single `@Transactional` that calls
three services. That breaks immediately:

1. **No shared transaction context.** HTTP doesn't carry a transaction. Each
   service's `@Transactional` is local. There is no `BEGIN` that can wrap them.
2. **Crashes between calls.** If service A commits and service B crashes before
   commit, you have a half-applied operation. Locally, the transaction would have
   rolled back; here, A's commit is durable and irreversible by the local DB.
3. **Network failures look like both success AND failure.** If A succeeds but the
   response is lost, the caller can't tell. Was it applied? Will retry double-apply?

This is why distributed atomicity is *fundamentally a different problem* from local
atomicity.

---

## Three answers to the distributed atomicity problem

### 2PC / XA — the heavyweight option

A coordinator runs two rounds:

1. **Prepare**: tell every resource "promise to be able to commit when I say so."
   Each resource holds DB locks until phase 2.
2. **Commit / Rollback**: based on prepare outcomes.

Strong correctness, but:
- Locks are held for the full protocol duration → kills throughput on contended rows
- Coordinator is a SPOF; recovery after coordinator crash is brittle
- Requires XA-aware resources (XA driver, JTA transaction manager)

### Saga — the eventually-consistent option

Each step is a local transaction. If step N fails, run compensating transactions for
steps 1…N-1. Async-friendly, no protocol locks. But:
- **No isolation**. Between step 2 committing and step 3 failing+compensating,
  *partial* state is visible to the rest of the system. Customers can see "your
  payment went through" before the order ships, then have to be told "actually, no."
- Compensations must always be possible — sometimes they're not (e.g., emails sent).

### TCC — the middle path (what this project implements)

Each business operation is decomposed into three explicit phases:

- **Try**: the *reservation*. Local transaction that decrements `available_qty` and
  increments `reserved_qty`. Releases DB locks immediately. Other actors see
  reduced availability — like a checkout cart "holds" stock for 15 minutes.
- **Confirm**: the *commit*. Local transaction that decrements `reserved_qty`
  (without changing `available_qty`). The reservation is now permanent.
- **Cancel**: the *release*. Local transaction that decrements `reserved_qty` and
  increments `available_qty` back. Returns to pre-Try state.

The key trade-off: the *isolation contract* moves up to the business model. The
business decides "reserved means unavailable to other shoppers." DBs no longer
know about the global transaction; the *coordinator* + *participant tombstones*
provide the atomicity guarantee.

---

## Why every TCC participant must be idempotent

The coordinator is not infallible. It will:

- Send the same Try twice (network retry)
- Send a Confirm after a previous Confirm response was lost
- Crash mid-Confirm and resume on restart (recovery loop)

If any of these double-apply, you've corrupted state. So **every** TCC operation
must be safe to call any number of times. The implementation pattern in this repo:

1. Use `tx_id` as the participant table's primary key. Insert is the
   atomic "first time" guard. Retries see the existing row and short-circuit.
2. Store the participant's local state (`TRIED` / `CONFIRMED` / `CANCELLED`) in
   that same row.
3. On Confirm/Cancel, `SELECT ... FOR UPDATE` the row, then dispatch on its state:
   - already terminal in the desired direction → no-op (idempotent)
   - terminal in the wrong direction → 409 (heuristic outcome — see below)
   - in `TRIED` → apply the side effect, transition state

---

## The "heuristic outcome" problem

A heuristic outcome is when a participant has reached a terminal state that
*conflicts* with what the coordinator wants. Two cases:

- **Cancel after Confirm**: the coordinator's Cancel arrives at a participant that
  has already been Confirmed. We can't un-commit; we must reject (HTTP 409). The
  global transaction is now in `HEURISTIC` state and **must not be auto-retried** —
  retries will fail forever, and a human must investigate (likely refund,
  manual reconciliation, etc.).
- **Confirm after Cancel**: similar mirror. Coordinator decided Confirm but a
  participant has already Cancelled (e.g., its row was tombstoned by a preventive
  cancel that won the race). 409 → HEURISTIC.

Many homegrown TCC implementations get this wrong: they either silently retry
forever, or they paper over the inconsistency. MicroTx's TCS surfaces it
explicitly. So does ours: a HEURISTIC tag and zero retries past that point.

---

## The "preventive cancel" / "tombstone" pattern

Here is a real race that surprises everyone the first time:

1. Coordinator sends Try to inventory; the request hangs in flight.
2. Coordinator times out, decides to cancel.
3. Coordinator sends Cancel to inventory. There's no row yet.
4. Cancel handler does nothing (`row not found, return CANCELLED`).
5. The original Try request finally arrives at inventory. It creates a TRIED row.
6. Now we have a `TRIED` row in inventory but the global transaction has finished
   as `CANCELLED`. Inventory has reserved stock that nobody is going to use.

The fix: step 4 must **insert a row in CANCELLED state** — a *tombstone*. Then
when the late Try arrives in step 5, it finds the existing row in CANCELLED and
rejects with 409. The race is closed.

This is what MicroTx's coordinator does internally; we do it explicitly in each
participant's `cancel()` handler (`if (row.isEmpty()) save(CANCELLED tombstone)`).

---

## Log-and-resume — the recovery primitive

The coordinator is also a service. It crashes. The recovery loop is what makes the
system tolerant of coordinator deaths.

The invariant: **persist state changes BEFORE doing the corresponding RPC**. So:

```
UPDATE global_transaction SET state='CONFIRMING' WHERE tx_id=?  ← persisted FIRST
... call participant.confirm()
```

If we crash between those two lines, recovery sees `state=CONFIRMING` and re-drives
the same `confirm()` call. Because the participant is idempotent, the resumed call
either short-circuits (already CONFIRMED) or applies the effect for the first time.

If we'd done it the other way around — RPC first, then UPDATE — and crashed
between, recovery would see the *old* state (e.g., `TRYING`) and not know whether
the Confirm RPC happened. No idempotency on participants can save us; we'd have to
re-derive the world somehow.

So: **log first, then RPC**. This rule, plus participant idempotency, is the entire
recovery story.
