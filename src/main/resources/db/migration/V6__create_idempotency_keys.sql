CREATE TABLE idempotency_keys (
                                  id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                  user_id       UUID         NOT NULL REFERENCES users (id),
                                  scope         VARCHAR(50)  NOT NULL,          -- which operation: HOLD_CREATE, PAYMENT_CREATE, ...
                                  idem_key      VARCHAR(100) NOT NULL,          -- the client's Idempotency-Key header
                                  request_hash  VARCHAR(64)  NOT NULL,          -- SHA-256 of the request body: detects key reuse with a different body
                                  response_body TEXT,                           -- replayable result for pure-DB operations
                                  resource_id   UUID,                           -- the created resource for multi-step operations (payments)
                                  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                  CONSTRAINT uq_idempotency_user_scope_key UNIQUE (user_id, scope, idem_key)
);

-- Purge job: "keys older than 24 h"
CREATE INDEX idx_idempotency_created_at ON idempotency_keys (created_at);