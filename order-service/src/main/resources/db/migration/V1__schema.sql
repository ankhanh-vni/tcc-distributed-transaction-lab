CREATE TABLE customer_order (
    id              UUID PRIMARY KEY,
    customer_id     VARCHAR(64) NOT NULL,
    product_sku     VARCHAR(64) NOT NULL,
    qty             INT NOT NULL DEFAULT 0 CHECK (qty >= 0),
    amount          NUMERIC(18,2) NOT NULL CHECK (amount >= 0),
    state           VARCHAR(16) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_order_state ON customer_order(state);
CREATE INDEX idx_customer_order_customer ON customer_order(customer_id);
