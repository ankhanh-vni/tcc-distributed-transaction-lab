CREATE TABLE global_transaction (
    tx_id           UUID PRIMARY KEY,
    business_key    VARCHAR(128),
    state           VARCHAR(32) NOT NULL,
    attempt_count   INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_global_tx_state ON global_transaction(state);
CREATE INDEX idx_global_tx_updated ON global_transaction(updated_at);

CREATE TABLE transaction_participant (
    id                BIGSERIAL PRIMARY KEY,
    tx_id             UUID NOT NULL REFERENCES global_transaction(tx_id) ON DELETE CASCADE,
    participant       VARCHAR(64) NOT NULL,
    base_url          VARCHAR(256) NOT NULL,
    resource_path     VARCHAR(256) NOT NULL,
    state             VARCHAR(32) NOT NULL,
    payload_json      TEXT NOT NULL,
    last_attempted_at TIMESTAMPTZ,
    last_error        TEXT,
    UNIQUE (tx_id, participant)
);

CREATE INDEX idx_tx_participant_tx ON transaction_participant(tx_id);
