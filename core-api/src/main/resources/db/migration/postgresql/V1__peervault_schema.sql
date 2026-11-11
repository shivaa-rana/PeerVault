CREATE TABLE account (
    id VARCHAR(36) PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    available_balance NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    ledger_balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
INSERT INTO account (id, type, currency) VALUES
    ('acc-merchant-1', 'MERCHANT', 'USD'),
    ('acc-fee-revenue', 'FEE_REVENUE', 'USD'),
    ('acc-network-settle', 'NETWORK_SETTLEMENT', 'USD');

CREATE TABLE card (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL REFERENCES account(id),
    pan_last_four VARCHAR(4) NOT NULL,
    pan_hash VARCHAR(64) NOT NULL,
    cvv_hash VARCHAR(64) NOT NULL,
    expiry_month INTEGER NOT NULL CHECK (expiry_month BETWEEN 1 AND 12),
    expiry_year INTEGER NOT NULL,
    tier VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_card_account ON card(account_id);

CREATE TABLE payment_transaction (
    id VARCHAR(36) PRIMARY KEY,
    card_id VARCHAR(36) NOT NULL REFERENCES card(id),
    merchant_id VARCHAR(64) NOT NULL,
    mcc VARCHAR(4),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    fee_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    decline_reason VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_card_created ON payment_transaction(card_id, created_at);

CREATE TABLE ledger_entry (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL REFERENCES payment_transaction(id),
    account_id VARCHAR(36) NOT NULL REFERENCES account(id),
    direction VARCHAR(1) NOT NULL CHECK (direction IN ('D', 'C')),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    posted_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_le_txn ON ledger_entry(transaction_id);

CREATE OR REPLACE FUNCTION prevent_legacy_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger entries are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER legacy_ledger_no_update
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION prevent_legacy_ledger_mutation();

CREATE TABLE idempotency_record (
    idempotency_key VARCHAR(80) PRIMARY KEY,
    endpoint VARCHAR(80) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INTEGER NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE webhook_delivery (
    id VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL,
    target_url VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMP,
    last_error VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    full_name VARCHAR(80) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_app_user_email UNIQUE (email)
);

CREATE TABLE wallet (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    currency VARCHAR(3) NOT NULL,
    available_balance NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_wallet_user_currency UNIQUE (user_id, currency)
);
CREATE INDEX idx_wallet_user ON wallet(user_id);

CREATE TABLE wallet_transfer (
    id UUID PRIMARY KEY,
    sender_wallet_id UUID NOT NULL REFERENCES wallet(id),
    recipient_wallet_id UUID NOT NULL REFERENCES wallet(id),
    initiated_by UUID NOT NULL REFERENCES app_user(id),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('INITIATED', 'PROCESSING', 'COMPLETED', 'FAILED', 'REVERSED')),
    failure_reason VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_transfer_distinct_wallets CHECK (sender_wallet_id <> recipient_wallet_id)
);
CREATE INDEX idx_transfer_sender_created ON wallet_transfer(sender_wallet_id, created_at DESC);
CREATE INDEX idx_transfer_recipient_created ON wallet_transfer(recipient_wallet_id, created_at DESC);

CREATE TABLE wallet_ledger_entry (
    id BIGSERIAL PRIMARY KEY,
    wallet_id UUID REFERENCES wallet(id),
    account_code VARCHAR(40),
    transfer_id UUID REFERENCES wallet_transfer(id),
    reference_id VARCHAR(80) NOT NULL,
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_one_account CHECK (
        (wallet_id IS NOT NULL AND account_code IS NULL) OR
        (wallet_id IS NULL AND account_code IS NOT NULL)
    ),
    CONSTRAINT uk_ledger_transfer_direction UNIQUE (transfer_id, direction)
);
CREATE INDEX idx_wallet_ledger_wallet_created ON wallet_ledger_entry(wallet_id, created_at DESC);
CREATE INDEX idx_wallet_ledger_reference ON wallet_ledger_entry(reference_id);

CREATE OR REPLACE FUNCTION prevent_wallet_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'wallet ledger entries are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER wallet_ledger_no_update
    BEFORE UPDATE OR DELETE ON wallet_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION prevent_wallet_ledger_mutation();

CREATE OR REPLACE FUNCTION validate_completed_transfer_ledger() RETURNS trigger AS $$
DECLARE
    target_transfer_id UUID;
    transfer_status VARCHAR(16);
    sender_id UUID;
    recipient_id UUID;
    transfer_amount NUMERIC(19,4);
    entry_count BIGINT;
    debit_count BIGINT;
    credit_count BIGINT;
    sender_debit_count BIGINT;
    recipient_credit_count BIGINT;
    currency_count BIGINT;
    debit_total NUMERIC(19,4);
    credit_total NUMERIC(19,4);
BEGIN
    IF TG_TABLE_NAME = 'wallet_transfer' THEN
        target_transfer_id := NEW.id;
    ELSIF TG_OP = 'INSERT' THEN
        target_transfer_id := NEW.transfer_id;
    ELSE
        target_transfer_id := OLD.transfer_id;
    END IF;
    IF target_transfer_id IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT status, sender_wallet_id, recipient_wallet_id, amount
        INTO transfer_status, sender_id, recipient_id, transfer_amount
        FROM wallet_transfer WHERE id = target_transfer_id;

    IF NOT FOUND OR transfer_status <> 'COMPLETED' THEN
        RETURN NULL;
    END IF;

    SELECT COUNT(*),
           COUNT(*) FILTER (WHERE direction = 'DEBIT'),
           COUNT(*) FILTER (WHERE direction = 'CREDIT'),
           COUNT(*) FILTER (WHERE direction = 'DEBIT' AND wallet_id = sender_id),
           COUNT(*) FILTER (WHERE direction = 'CREDIT' AND wallet_id = recipient_id),
           COUNT(*) FILTER (WHERE currency = (SELECT currency FROM wallet_transfer WHERE id = target_transfer_id)),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
        INTO entry_count, debit_count, credit_count, sender_debit_count,
             recipient_credit_count, currency_count, debit_total, credit_total
        FROM wallet_ledger_entry WHERE transfer_id = target_transfer_id;

    IF entry_count <> 2 OR debit_count <> 1 OR credit_count <> 1
       OR sender_debit_count <> 1 OR recipient_credit_count <> 1
       OR currency_count <> 2
       OR debit_total <> transfer_amount OR credit_total <> transfer_amount THEN
        RAISE EXCEPTION 'completed transfer % must have one balanced debit and credit',
            target_transfer_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER completed_transfer_ledger_check
    AFTER INSERT OR UPDATE ON wallet_transfer
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_completed_transfer_ledger();
CREATE CONSTRAINT TRIGGER wallet_ledger_transfer_check
    AFTER INSERT ON wallet_ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_completed_transfer_ledger();

CREATE OR REPLACE FUNCTION prevent_terminal_transfer_mutation() RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('COMPLETED', 'FAILED', 'REVERSED') THEN
        RAISE EXCEPTION 'terminal transfers are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER terminal_transfer_no_update
    BEFORE UPDATE ON wallet_transfer
    FOR EACH ROW EXECUTE FUNCTION prevent_terminal_transfer_mutation();

CREATE TABLE transfer_idempotency (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_transfer_idempotency UNIQUE (user_id, operation, idempotency_key)
);

CREATE TABLE fraud_flag (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    transfer_id UUID REFERENCES wallet_transfer(id),
    risk_reason VARCHAR(120) NOT NULL,
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    review_status VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (review_status IN ('OPEN', 'REVIEWED', 'DISMISSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id UUID REFERENCES app_user(id),
    action VARCHAR(80) NOT NULL,
    subject_id VARCHAR(80),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_created ON audit_log(created_at DESC);

CREATE TABLE platform_account (
    account_code VARCHAR(40) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    PRIMARY KEY (account_code, currency)
);
INSERT INTO platform_account (account_code, currency) VALUES
    ('PAYMENT_CLEARING', 'INR'),
    ('PAYMENT_CLEARING', 'USD');

CREATE TABLE payment_intent (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    wallet_id UUID NOT NULL REFERENCES wallet(id),
    provider VARCHAR(20) NOT NULL,
    provider_intent_id VARCHAR(120) NOT NULL UNIQUE,
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_event (
    provider_event_id VARCHAR(120) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
