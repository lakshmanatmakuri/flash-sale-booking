CREATE TABLE seat_holds (
                            id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                            event_seat_id UUID           NOT NULL REFERENCES event_seats (id),
                            event_id      UUID           NOT NULL REFERENCES events (id),
                            user_id       UUID           NOT NULL REFERENCES users (id),
                            status        VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
                            locked_price  NUMERIC(19, 4) NOT NULL,       -- the price the user will pay, frozen at hold time
                            currency      CHAR(3)        NOT NULL DEFAULT 'INR',
                            expires_at    TIMESTAMPTZ    NOT NULL,
                            renewal_count INTEGER        NOT NULL DEFAULT 0,
                            created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
                            updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
                            CONSTRAINT ck_seat_holds_status   CHECK (status IN ('ACTIVE', 'CONVERTED', 'EXPIRED', 'RELEASED')),
                            CONSTRAINT ck_seat_holds_price    CHECK (locked_price >= 0),
                            CONSTRAINT ck_seat_holds_renewals CHECK (renewal_count >= 0)
);

-- THE BACKSTOP: a seat can have at most ONE active hold, enforced by the database itself.
CREATE UNIQUE INDEX uq_seat_holds_one_active_per_seat
    ON seat_holds (event_seat_id) WHERE status = 'ACTIVE';

-- Sweeper: "active holds that are due", over a tiny index containing only live holds.
CREATE INDEX idx_seat_holds_active_expiry
    ON seat_holds (expires_at) WHERE status = 'ACTIVE';

-- Per-user limit: "how many active holds does this user have for this event?"
CREATE INDEX idx_seat_holds_user_event_active
    ON seat_holds (user_id, event_id) WHERE status = 'ACTIVE';

-- History lookups per seat (also serves the FK).
CREATE INDEX idx_seat_holds_event_seat ON seat_holds (event_seat_id);