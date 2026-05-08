CREATE TABLE product (
    id              BIGSERIAL PRIMARY KEY,
    sku             VARCHAR(64) UNIQUE NOT NULL,
    available_qty   INT NOT NULL CHECK (available_qty >= 0),
    reserved_qty    INT NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0)
);

CREATE TABLE inventory_reservation (
    tx_id           UUID PRIMARY KEY,
    product_id      BIGINT REFERENCES product(id),
    qty             INT NOT NULL DEFAULT 0 CHECK (qty >= 0),
    state           VARCHAR(16) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inventory_reservation_state ON inventory_reservation(state);

INSERT INTO product (sku, available_qty, reserved_qty) VALUES
    ('SKU-A', 10, 0),
    ('SKU-B', 5, 0);
