CREATE TABLE order_idempotency (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_fingerprint TEXT NOT NULL,
    tx_id UUID NOT NULL UNIQUE REFERENCES global_transaction(tx_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- No TTL: forgetting a key would let a delayed retry create a second order.
