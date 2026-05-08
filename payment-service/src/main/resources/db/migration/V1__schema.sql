CREATE TABLE account (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     VARCHAR(64) UNIQUE NOT NULL,
    balance         NUMERIC(18,2) NOT NULL CHECK (balance >= 0),
    frozen_amount   NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (frozen_amount >= 0)
);

CREATE TABLE payment_authorization (
    tx_id           UUID PRIMARY KEY,
    account_id      BIGINT REFERENCES account(id),
    amount          NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    state           VARCHAR(16) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_auth_state ON payment_authorization(state);

INSERT INTO account (customer_id, balance, frozen_amount) VALUES
    ('CUST-1', 1000.00, 0),
    ('CUST-2', 50.00, 0);
