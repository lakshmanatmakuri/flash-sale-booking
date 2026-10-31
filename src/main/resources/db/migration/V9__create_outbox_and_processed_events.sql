CREATE TABLE outbox_events (
                               id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),   -- the MESSAGE id consumers dedupe on
                               seq             BIGINT       GENERATED ALWAYS AS IDENTITY,            -- publish order
                               aggregate_type  VARCHAR(30)  NOT NULL,
                               aggregate_id    VARCHAR(100) NOT NULL,                                -- becomes the Kafka key
                               event_type      VARCHAR(50)  NOT NULL,
                               payload         JSONB        NOT NULL,
                               status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
                               attempts        INTEGER      NOT NULL DEFAULT 0,
                               next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
                               locked_until    TIMESTAMPTZ,                                          -- publisher lease
                               last_error      VARCHAR(500),
                               created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                               sent_at         TIMESTAMPTZ,
                               CONSTRAINT uq_outbox_seq UNIQUE (seq),
                               CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'SENT'))
);

-- The publisher only ever scans unsent rows: a tiny index however large the history grows.
CREATE INDEX idx_outbox_pending ON outbox_events (seq) WHERE status = 'PENDING';
-- Debugging: "everything that happened to seat X"
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id, seq);

CREATE TABLE processed_events (
                                  consumer     VARCHAR(50)  NOT NULL,
                                  message_id   UUID         NOT NULL,
                                  processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                  PRIMARY KEY (consumer, message_id)      -- the dedupe guarantee lives in the database
);
CREATE INDEX idx_processed_events_age ON processed_events (processed_at);