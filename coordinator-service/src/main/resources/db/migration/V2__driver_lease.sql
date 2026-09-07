ALTER TABLE global_transaction ADD COLUMN driver_token UUID;
ALTER TABLE global_transaction ADD COLUMN lease_until TIMESTAMPTZ;
CREATE INDEX idx_global_recovery ON global_transaction(updated_at)
    WHERE state IN ('STARTED', 'TRYING', 'TRY_FAILED', 'CONFIRMING', 'CANCELLING');
ALTER TABLE global_transaction ALTER COLUMN business_key TYPE VARCHAR(135);
