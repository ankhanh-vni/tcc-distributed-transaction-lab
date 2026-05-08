# Sequence Diagrams

## 1. Happy path — all Try succeed, all Confirm succeed

```mermaid
sequenceDiagram
    participant C as Client
    participant Co as Coordinator
    participant DB as coordinator_db
    participant I as inventory-service
    participant P as payment-service
    participant O as order-service

    C->>Co: POST /api/orders
    Co->>DB: INSERT global_transaction(STARTED) + 3 participants(PENDING)
    Co->>DB: UPDATE state=TRYING
    Co->>I: POST /tcc/inventory/reservations
    I-->>Co: 200 TRIED
    Co->>DB: UPDATE inventory state=TRIED
    Co->>P: POST /tcc/payment/authorizations
    P-->>Co: 200 TRIED
    Co->>DB: UPDATE payment state=TRIED
    Co->>O: POST /tcc/orders
    O-->>Co: 200 TRIED
    Co->>DB: UPDATE order state=TRIED
    Co->>DB: UPDATE state=CONFIRMING
    Co->>I: PUT /tcc/inventory/reservations/{tx}/confirm
    I-->>Co: 200 CONFIRMED
    Co->>P: PUT /tcc/payment/authorizations/{tx}/confirm
    P-->>Co: 200 CONFIRMED
    Co->>O: PUT /tcc/orders/{tx}/confirm
    O-->>Co: 200 CONFIRMED
    Co->>DB: UPDATE state=CONFIRMED
    Co-->>C: 200 {state: CONFIRMED}
```

## 2. Payment Try fails — Cancel everyone

```mermaid
sequenceDiagram
    participant C as Client
    participant Co as Coordinator
    participant DB as coordinator_db
    participant I as inventory-service
    participant P as payment-service
    participant O as order-service

    C->>Co: POST /api/orders
    Co->>DB: INSERT STARTED + PENDING×3
    Co->>DB: UPDATE state=TRYING
    Co->>I: POST /tcc/inventory/reservations
    I-->>Co: 200 TRIED
    Co->>P: POST /tcc/payment/authorizations
    P-->>Co: 422 (insufficient balance)
    Co->>DB: UPDATE payment state=FAILED, global state=CANCELLING
    Co->>I: DELETE /tcc/inventory/reservations/{tx}
    I-->>Co: 200 CANCELLED  (releases reservation)
    Co->>P: DELETE /tcc/payment/authorizations/{tx}
    P-->>Co: 200 CANCELLED  (preventive tombstone)
    Co->>O: DELETE /tcc/orders/{tx}
    O-->>Co: 200 CANCELLED  (preventive tombstone — defeats late Try)
    Co->>DB: UPDATE state=CANCELLED
    Co-->>C: 200 {state: CANCELLED}
```

## 3. Confirm timeout — recovery re-drives

```mermaid
sequenceDiagram
    participant C as Client
    participant Co as Coordinator
    participant DB as coordinator_db
    participant Sched as RecoveryService
    participant I as inventory-service

    C->>Co: POST /api/orders (X-Inject-Confirm-Sleep-Millis: 8000)
    Co->>DB: STARTED → TRYING → all TRIED → CONFIRMING
    Co->>I: PUT /tcc/.../confirm  (X-Sleep-Millis: 8000)
    Note right of I: Filter sleeps 8s; coordinator's read timeout is 2s
    Co--xI: read timeout
    Co->>DB: mark inventory.last_error="timeout"; state stays CONFIRMING
    Co-->>C: 200 {state: CONFIRMING}  (not yet terminal)

    loop every 5s (fixedDelay)
        Sched->>DB: SELECT stuck TX (CONFIRMING + updated_at < now()-8s)
        Sched->>Co: drive(txId)
        Co->>I: PUT /tcc/.../confirm  (no sleep header this time)
        I-->>Co: 200 CONFIRMED
        Co->>DB: UPDATE state=CONFIRMED
    end
```

## 4. Coordinator crash during TRYING — recovery resumes

```mermaid
sequenceDiagram
    participant C as Client
    participant Co1 as Coordinator (crashes)
    participant DB as coordinator_db
    participant Co2 as Coordinator (restarted)
    participant Sched as RecoveryService
    participant I as inventory-service
    participant P as payment-service
    participant O as order-service

    C->>Co1: POST /api/orders
    Co1->>DB: INSERT STARTED + PENDING×3
    Co1->>DB: UPDATE state=TRYING
    Note over Co1: 💥 process killed
    C--xCo1: connection reset

    Note over Co2: process restarts
    loop every 5s
        Sched->>DB: SELECT stuck TX (TRYING + updated_at old)
        Sched->>Co2: drive(txId)
        Co2->>I: POST .../reservations  (idempotent)
        I-->>Co2: 200 TRIED
        Co2->>P: POST .../authorizations
        P-->>Co2: 200 TRIED
        Co2->>O: POST .../orders
        O-->>Co2: 200 TRIED
        Co2->>DB: UPDATE state=CONFIRMING
        Co2->>I: PUT .../confirm
        Co2->>P: PUT .../confirm
        Co2->>O: PUT .../confirm
        Co2->>DB: UPDATE state=CONFIRMED
    end
```

## 5. Coordinator state machine

```mermaid
stateDiagram-v2
    [*] --> STARTED
    STARTED --> TRYING: log first
    TRYING --> CONFIRMING: all TRIED
    TRYING --> CANCELLING: any FAILED
    CONFIRMING --> CONFIRMED: all CONFIRMED
    CONFIRMING --> HEURISTIC: 409 from participant
    CANCELLING --> CANCELLED: all CANCELLED
    CANCELLING --> HEURISTIC: 409 from participant
    CONFIRMED --> [*]
    CANCELLED --> [*]
    HEURISTIC --> [*]: needs human intervention
```

## 6. Participant state machine

```mermaid
stateDiagram-v2
    [*] --> empty: initial (no row)
    empty --> TRIED: Try succeeds
    empty --> CANCELLED: Cancel arrives first (preventive tombstone)
    TRIED --> CONFIRMED: Confirm
    TRIED --> CANCELLED: Cancel
    TRIED --> TRIED: Try retry (idempotent)
    CONFIRMED --> CONFIRMED: Confirm retry (idempotent)
    CANCELLED --> CANCELLED: Cancel retry (idempotent)
    CONFIRMED --> ⛔: Cancel rejected (409, → coordinator HEURISTIC)
    CANCELLED --> ⛔: Confirm rejected (409, → coordinator HEURISTIC)
```
