-- A journal is one atomic business event; its entries must net to zero.
CREATE TABLE journals (
    id           uuid         PRIMARY KEY,
    type         varchar(16)  NOT NULL CHECK (type IN ('TRANSFER','DEPOSIT','REVERSAL')),
    reference    varchar(40)  NOT NULL UNIQUE,
    description  varchar(255),
    -- UNIQUE => a journal can be reversed at most once, enforced by the database.
    reversal_of  uuid         UNIQUE REFERENCES journals (id),
    created_at   timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id             bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_id     uuid         NOT NULL REFERENCES journals (id),
    account_id     uuid         NOT NULL REFERENCES accounts (id),
    direction      varchar(6)   NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount         bigint       NOT NULL CHECK (amount > 0),
    balance_after  bigint,                                  -- null for untracked system accounts
    line_type      varchar(16)  NOT NULL,                   -- TOTAL, PRINCIPAL, STAMP_DUTY, FEE, VAT
    created_at     timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_entries_account ON ledger_entries (account_id, id DESC);   -- statements (keyset pagination)
CREATE INDEX idx_entries_journal ON ledger_entries (journal_id);

-- ---------------------------------------------------------------------------
-- Append-only: UPDATE / DELETE / TRUNCATE are rejected at the database level.
-- Corrections are new reversing journals, never edits.
-- ---------------------------------------------------------------------------
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: the ledger is append-only', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_entries_no_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_journals_immutable BEFORE UPDATE OR DELETE ON journals
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_journals_no_truncate BEFORE TRUNCATE ON journals
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- ---------------------------------------------------------------------------
-- Double-entry invariant enforced at COMMIT: debits must equal credits per journal.
-- Deferred so all lines of a journal can be inserted one by one first.
-- ---------------------------------------------------------------------------
CREATE FUNCTION assert_journal_balanced() RETURNS trigger AS $$
DECLARE
    d bigint;
    c bigint;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO d, c
      FROM ledger_entries
     WHERE journal_id = NEW.journal_id;

    IF d <> c THEN
        RAISE EXCEPTION 'Journal % is unbalanced: debits=% credits=%', NEW.journal_id, d, c;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_journal_balanced();
