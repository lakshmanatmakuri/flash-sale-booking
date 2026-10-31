CREATE TABLE ledger_accounts (
                                 id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                 code                 VARCHAR(100) NOT NULL,
                                 type                 VARCHAR(10)  NOT NULL,
                                 owner_type           VARCHAR(10)  NOT NULL,
                                 owner_id             UUID,
                                 currency             CHAR(3)      NOT NULL,
                                 enforce_non_negative BOOLEAN      NOT NULL DEFAULT false,
                                 created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                 CONSTRAINT uq_ledger_accounts_code        UNIQUE (code),
                                 CONSTRAINT uq_ledger_accounts_id_currency UNIQUE (id, currency),   -- target of the composite FK below
                                 CONSTRAINT ck_ledger_accounts_type  CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
                                 CONSTRAINT ck_ledger_accounts_owner CHECK (owner_type IN ('SYSTEM', 'USER', 'ORGANIZER')),
                                 CONSTRAINT ck_ledger_accounts_owner_id CHECK ((owner_type = 'SYSTEM') = (owner_id IS NULL))
);

INSERT INTO ledger_accounts (code, type, owner_type, currency) VALUES
                                                                   ('PROVIDER_CLEARING',    'ASSET',     'SYSTEM', 'INR'),
                                                                   ('ESCROW',               'LIABILITY', 'SYSTEM', 'INR'),
                                                                   ('PLATFORM_FEE_REVENUE', 'REVENUE',   'SYSTEM', 'INR');

CREATE TABLE ledger_transactions (
                                     id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                     type                    VARCHAR(30)  NOT NULL,
                                     idempotency_key         VARCHAR(200) NOT NULL,     -- the same business event can never be posted twice
                                     reference_type          VARCHAR(30)  NOT NULL,
                                     reference_id            VARCHAR(200) NOT NULL,
                                     description             VARCHAR(255),
                                     reverses_transaction_id UUID REFERENCES ledger_transactions (id),
                                     created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                     CONSTRAINT uq_ledger_tx_idempotency UNIQUE (idempotency_key),
                                     CONSTRAINT ck_ledger_tx_type CHECK (type IN ('WALLET_TOPUP', 'PAYMENT_CAPTURE', 'BOOKING_SETTLEMENT', 'REVERSAL')),
                                     CONSTRAINT ck_ledger_tx_reversal CHECK ((type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL))
);
-- A transaction can be reversed at most once, enforced by the database.
CREATE UNIQUE INDEX uq_ledger_tx_reverses ON ledger_transactions (reverses_transaction_id)
    WHERE reverses_transaction_id IS NOT NULL;
CREATE INDEX idx_ledger_tx_reference ON ledger_transactions (reference_type, reference_id);

CREATE TABLE ledger_entries (
                                id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                transaction_id UUID           NOT NULL REFERENCES ledger_transactions (id),
                                account_id     UUID           NOT NULL,
                                direction      VARCHAR(6)     NOT NULL,
                                amount         NUMERIC(19, 4) NOT NULL,
                                currency       CHAR(3)        NOT NULL,
                                created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- An entry's currency must equal its account's currency (no mixing, enforced by the DB).
                                CONSTRAINT fk_ledger_entries_account FOREIGN KEY (account_id, currency)
                                    REFERENCES ledger_accounts (id, currency),
                                CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
                                CONSTRAINT ck_ledger_entries_amount    CHECK (amount > 0)       -- direction carries the sign, never the amount
);
CREATE INDEX idx_ledger_entries_tx ON ledger_entries (transaction_id);
-- Covering index: a balance SUM is answered from the index alone.
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id) INCLUDE (direction, amount);

-- ---------------------------------------------------------------------------------------------
-- IMMUTABILITY: the ledger is append-only. Even the application's own DB user cannot edit history.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ledger_forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
RETURN NULL;
END $$;

CREATE TRIGGER trg_ledger_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();
CREATE TRIGGER trg_ledger_entries_no_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_mutation();
CREATE TRIGGER trg_ledger_tx_immutable BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();
CREATE TRIGGER trg_ledger_tx_no_truncate BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_mutation();

-- ---------------------------------------------------------------------------------------------
-- BALANCE: debits = credits per transaction (per currency), checked AT COMMIT.
-- DEFERRED matters: while the transaction is still being built it is temporarily unbalanced
-- (the first entry is inserted alone). The check runs when the whole transaction tries to commit.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ledger_check_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
tx_id       UUID;
    entry_count INTEGER;
    unbalanced  INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'ledger_transactions' THEN tx_id := NEW.id; ELSE tx_id := NEW.transaction_id; END IF;

SELECT count(*) INTO entry_count FROM ledger_entries WHERE transaction_id = tx_id;
IF entry_count < 2 THEN
        RAISE EXCEPTION 'ledger transaction % has % entries; at least 2 are required', tx_id, entry_count
            USING ERRCODE = 'integrity_constraint_violation';
END IF;

SELECT count(*) INTO unbalanced FROM (
                                         SELECT currency FROM ledger_entries WHERE transaction_id = tx_id
                                         GROUP BY currency
                                         HAVING sum(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
                                     ) t;
IF unbalanced > 0 THEN
        RAISE EXCEPTION 'ledger transaction % is not balanced: debits <> credits', tx_id
            USING ERRCODE = 'integrity_constraint_violation';
END IF;
RETURN NULL;
END $$;

-- Fires for the transaction row (catches "transaction with no entries") and for every entry.
CREATE CONSTRAINT TRIGGER trg_ledger_tx_balanced AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_check_balanced();
CREATE CONSTRAINT TRIGGER trg_ledger_entry_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_check_balanced();