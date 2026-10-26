-- One row per (event, seat). THIS is the table the whole concurrency story hangs on.
CREATE TABLE event_seats (
                             id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                             event_id   UUID           NOT NULL REFERENCES events (id) ON DELETE CASCADE,
                             seat_id    UUID           NOT NULL REFERENCES seats (id),
                             status     VARCHAR(20)    NOT NULL DEFAULT 'AVAILABLE',
                             base_price NUMERIC(19, 4) NOT NULL,
                             currency   CHAR(3)        NOT NULL DEFAULT 'INR',
                             version    BIGINT         NOT NULL DEFAULT 0,
                             updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
                             CONSTRAINT uq_event_seat UNIQUE (event_id, seat_id),
                             CONSTRAINT ck_event_seats_status CHECK (status IN
                                                                     ('AVAILABLE', 'HELD', 'PAYMENT_PENDING', 'BOOKED', 'CANCELLED')),
                             CONSTRAINT ck_event_seats_price CHECK (base_price >= 0)
);

-- "How many seats are available for event X?" and "list available seats for event X"
CREATE INDEX idx_event_seats_event_status ON event_seats (event_id, status);