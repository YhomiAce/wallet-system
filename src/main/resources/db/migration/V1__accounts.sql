-- Every wallet and every internal (system) account is a row in `accounts`.
-- Money is stored as BIGINT kobo. Never floats, never NUMERIC-with-scale-surprises.

CREATE SEQUENCE account_number_seq START WITH 1000000001 INCREMENT BY 1 NO CYCLE;

CREATE TABLE accounts (
    id               uuid         PRIMARY KEY,
    code             varchar(64)  UNIQUE,                 -- system accounts only
    account_number   varchar(10)  UNIQUE,                 -- wallets only
    owner_id         varchar(64),                         -- customer id, wallets only
    kind             varchar(8)   NOT NULL CHECK (kind IN ('WALLET', 'SYSTEM')),
    type             varchar(10)  NOT NULL CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    normal_side      varchar(6)   NOT NULL CHECK (normal_side IN ('DEBIT','CREDIT')),
    currency         varchar(3)   NOT NULL DEFAULT 'NGN',
    -- Cached projection of the ledger, expressed on the account's normal side.
    balance          bigint       NOT NULL DEFAULT 0,
    -- false for hot system accounts: no balance column maintenance, no row lock.
    -- Their balance is derived from the ledger on demand.
    balance_tracked  boolean      NOT NULL DEFAULT true,
    allow_negative   boolean      NOT NULL DEFAULT false,
    status           varchar(8)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    version          bigint       NOT NULL DEFAULT 0,     -- bumps on every balance change; used to order cache writes
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),

    -- Last line of defence: even a buggy service cannot overdraw a wallet.
    CONSTRAINT chk_non_negative CHECK (allow_negative OR balance >= 0),
    CONSTRAINT chk_identity CHECK (
        (kind = 'WALLET' AND account_number IS NOT NULL AND owner_id IS NOT NULL)
        OR (kind = 'SYSTEM' AND code IS NOT NULL)
    )
);

-- One wallet per customer per currency.
CREATE UNIQUE INDEX uq_wallet_owner_currency ON accounts (owner_id, currency) WHERE kind = 'WALLET';
