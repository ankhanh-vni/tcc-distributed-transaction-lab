# curl examples

Bring the system up first:

```bash
mvn -DskipTests package
docker compose up --build -d
# Wait ~20s for services to be healthy
curl http://localhost:8081/actuator/health  # inventory
```

Re-seed fixtures between scenarios:

```bash
docker exec -i $(docker compose ps -q postgres) psql -U inventory -d inventory_db \
  -c "update product set available_qty=10, reserved_qty=0 where sku='SKU-A';"
docker exec -i $(docker compose ps -q postgres) psql -U payment -d payment_db \
  -c "update account set balance=1000.00, frozen_amount=0 where customer_id='CUST-1';"
```

---

## Scenario 1 — happy path

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1","sku":"SKU-A","qty":1,"amount":100.00}'
```

Expected: `{"state":"CONFIRMED",...}`. Verify:

```bash
TX=<txId-from-above>
curl -s http://localhost:8080/api/transactions/$TX | jq
docker exec -i $(docker compose ps -q postgres) psql -U coordinator -d coordinator_db \
  -c "select state, attempt_count from global_transaction where tx_id='$TX';"
docker exec -i $(docker compose ps -q postgres) psql -U inventory -d inventory_db \
  -c "select available_qty, reserved_qty from product where sku='SKU-A';"
```

Expected: `available=9, reserved=0`, `state=CONFIRMED`.

---

## Scenario 2 — payment Try fails (insufficient balance)

CUST-2 has only $50; ask for $200 → payment rejects, coordinator cancels everyone.

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-2","sku":"SKU-B","qty":1,"amount":200.00}'
```

Expected: `{"state":"CANCELLED",...}`. Verify inventory was rolled back:

```bash
docker exec -i $(docker compose ps -q postgres) psql -U inventory -d inventory_db \
  -c "select available_qty, reserved_qty from product where sku='SKU-B';"
```

Expected: stock unchanged (`available=5, reserved=0`).

---

## Scenario 3 — duplicate Confirm (idempotency)

Direct against participant:

```bash
TX=$(uuidgen)
# Try
curl -s -X POST http://localhost:8081/tcc/inventory/reservations \
  -H 'Content-Type: application/json' \
  -d "{\"txId\":\"$TX\",\"sku\":\"SKU-A\",\"qty\":1}"
# Confirm twice
curl -s -X PUT http://localhost:8081/tcc/inventory/reservations/$TX/confirm
curl -s -X PUT http://localhost:8081/tcc/inventory/reservations/$TX/confirm
```

Both Confirms return 200 with `state: CONFIRMED`. Stock is decremented exactly once.

---

## Scenario 4 — duplicate Cancel (idempotency)

```bash
TX=$(uuidgen)
curl -s -X POST http://localhost:8081/tcc/inventory/reservations \
  -H 'Content-Type: application/json' \
  -d "{\"txId\":\"$TX\",\"sku\":\"SKU-A\",\"qty\":2}"
curl -s -X DELETE http://localhost:8081/tcc/inventory/reservations/$TX
curl -s -X DELETE http://localhost:8081/tcc/inventory/reservations/$TX
```

Both Cancels return 200. Stock is restored exactly once.

---

## Scenario 5 — Confirm after Cancel → 409

```bash
TX=$(uuidgen)
curl -s -X POST http://localhost:8081/tcc/inventory/reservations \
  -H 'Content-Type: application/json' \
  -d "{\"txId\":\"$TX\",\"sku\":\"SKU-A\",\"qty\":1}"
curl -s -X DELETE http://localhost:8081/tcc/inventory/reservations/$TX
curl -i -X PUT http://localhost:8081/tcc/inventory/reservations/$TX/confirm
```

Last response is `409 Conflict` with `{"code":"CONFIRM_AFTER_CANCEL",...}`.

---

## Scenario 6 — Cancel after Confirm → 409

```bash
TX=$(uuidgen)
curl -s -X POST http://localhost:8081/tcc/inventory/reservations \
  -H 'Content-Type: application/json' \
  -d "{\"txId\":\"$TX\",\"sku\":\"SKU-A\",\"qty\":1}"
curl -s -X PUT http://localhost:8081/tcc/inventory/reservations/$TX/confirm
curl -i -X DELETE http://localhost:8081/tcc/inventory/reservations/$TX
```

Last response is `409 Conflict` with `{"code":"CANCEL_AFTER_CONFIRM",...}`.

---

## Scenario 7 — Confirm timeout, recovery saves the day

Coordinator's read timeout is 2s; tell participants to sleep 8s on Confirm.

```bash
RESP=$(curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -H 'X-Inject-Confirm-Sleep-Millis: 8000' \
  -d '{"customerId":"CUST-1","sku":"SKU-A","qty":1,"amount":50.00}')
TX=$(echo "$RESP" | jq -r .txId)
echo "$RESP"  # state will be CONFIRMING (not yet terminal)
```

Wait ~15s and poll:

```bash
for i in 1 2 3 4 5 6 7 8 9 10; do
  curl -s http://localhost:8080/api/transactions/$TX | jq -r .state
  sleep 5
done
```

Expected: state transitions to `CONFIRMED` once the recovery loop re-drives.

---

## Scenario 8 — coordinator crash simulation

Seed the coordinator log with a TRYING transaction and trigger recovery:

```bash
TX=$(uuidgen)
docker exec -i $(docker compose ps -q postgres) psql -U coordinator -d coordinator_db <<SQL
INSERT INTO global_transaction (tx_id, business_key, state, created_at, updated_at)
VALUES ('$TX', 'manual-crash-sim', 'TRYING', now()-interval '30 seconds', now()-interval '30 seconds');
INSERT INTO transaction_participant (tx_id, participant, base_url, resource_path, state, payload_json) VALUES
  ('$TX', 'inventory', 'http://inventory-service:8081', '/tcc/inventory/reservations', 'PENDING',
   '{"txId":"$TX","sku":"SKU-A","qty":1}'),
  ('$TX', 'payment', 'http://payment-service:8082', '/tcc/payment/authorizations', 'PENDING',
   '{"txId":"$TX","customerId":"CUST-1","amount":1.00}'),
  ('$TX', 'order', 'http://order-service:8083', '/tcc/orders', 'PENDING',
   '{"txId":"$TX","customerId":"CUST-1","sku":"SKU-A","qty":1,"amount":1.00}');
SQL

curl -s -X POST http://localhost:8080/api/admin/recovery/run | jq
sleep 3
curl -s http://localhost:8080/api/transactions/$TX | jq
```

Expected: recovery returns `{"driven":1}`; status reaches `CONFIRMED`.

---

## Bonus — failure-injection per phase via headers

Force payment to fail Try via the participant's failure filter:

```bash
TX=$(uuidgen)
# Direct call to payment with the per-request fail header:
curl -i -X POST http://localhost:8082/tcc/payment/authorizations \
  -H 'Content-Type: application/json' \
  -H 'X-Fail-At: TRY' \
  -d "{\"txId\":\"$TX\",\"customerId\":\"CUST-1\",\"amount\":1.00}"
# → 500 with "injected failure at TRY"
```

Or via coordinator-forwarded failure (applies to all 3 participants in our simple
implementation):

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -H 'X-Inject-Fail-At-Participant: TRY' \
  -d '{"customerId":"CUST-1","sku":"SKU-A","qty":1,"amount":100.00}'
# → state=CANCELLED (inventory Try fails immediately, coordinator cancels)
```
