-- Demat accounts and the shares in them. Every change is a transfer with its instruction id, and
-- every account's movements are kept, so any holding can be explained line by line.

CREATE TABLE accounts (
    bo_id        text PRIMARY KEY CHECK (bo_id ~ '^[0-9]{16}$'),
    participant  text,                      -- null for the clearing corporation's settlement account
    client_ref   text,
    holder_name  text NOT NULL,
    settlement   boolean NOT NULL DEFAULT false,
    opened_at    timestamptz NOT NULL,
    UNIQUE (participant, client_ref)
);

CREATE TABLE holdings (
    bo_id     text NOT NULL REFERENCES accounts (bo_id),
    symbol    text NOT NULL,
    quantity  bigint NOT NULL,
    PRIMARY KEY (bo_id, symbol)
);

CREATE TABLE transfers (
    instruction_id  text PRIMARY KEY,
    request_hash    text NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('PAY_IN', 'PAY_OUT')),
    bo_id           text NOT NULL REFERENCES accounts (bo_id),
    symbol          text NOT NULL,
    quantity        bigint NOT NULL CHECK (quantity > 0),
    settlement_ref  text NOT NULL,
    executed_at     timestamptz NOT NULL
);

CREATE INDEX transfers_by_settlement ON transfers (settlement_ref);

CREATE TABLE movements (
    id              bigserial PRIMARY KEY,
    bo_id           text NOT NULL REFERENCES accounts (bo_id),
    symbol          text NOT NULL,
    quantity        bigint NOT NULL,
    balance_after   bigint NOT NULL,
    kind            text NOT NULL,
    instruction_id  text NOT NULL REFERENCES transfers (instruction_id),
    at              timestamptz NOT NULL
);

CREATE INDEX movements_by_account ON movements (bo_id, id DESC);

-- each participant numbers its clients' accounts
CREATE SEQUENCE client_numbers;
