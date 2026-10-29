CREATE TABLE bookings (
                          id               UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                          user_id          UUID           NOT NULL REFERENCES users (id),
                          event_id         UUID           NOT NULL REFERENCES events (id),
                          status           VARCHAR(20)    NOT NULL DEFAULT 'PENDING_PAYMENT',
                          total_amount     NUMERIC(19, 4) NOT NULL,
                          currency         CHAR(3)        NOT NULL DEFAULT 'INR',
                          group_booking_id UUID,                          -- FK is added in Phase 10
                          version          BIGINT         NOT NULL DEFAULT 0,
                          created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
                          updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
                          confirmed_at     TIMESTAMPTZ,
                          CONSTRAINT ck_bookings_status CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'FAILED', 'CANCELLED')),
                          CONSTRAINT ck_bookings_total  CHECK (total_amount > 0)
);
CREATE INDEX idx_bookings_user_created  ON bookings (user_id, created_at DESC);   -- "my bookings"
CREATE INDEX idx_bookings_event_status  ON bookings (event_id, status);           -- organizer / reconciliation

CREATE TABLE booking_items (
                               id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                               booking_id    UUID           NOT NULL REFERENCES bookings (id),
                               event_seat_id UUID           NOT NULL REFERENCES event_seats (id),
                               hold_id       UUID           NOT NULL REFERENCES seat_holds (id),
                               price         NUMERIC(19, 4) NOT NULL,
                               status        VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
                               created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
                               updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
                               CONSTRAINT ck_booking_items_status CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED')),
                               CONSTRAINT ck_booking_items_price  CHECK (price >= 0)
);
-- BACKSTOP #2 for double booking: a seat can belong to at most ONE live (pending or confirmed) booking item.
CREATE UNIQUE INDEX uq_booking_items_one_live_per_seat
    ON booking_items (event_seat_id) WHERE status IN ('PENDING', 'CONFIRMED');
-- A hold converts into a booking at most once.
CREATE UNIQUE INDEX uq_booking_items_hold ON booking_items (hold_id);
CREATE INDEX idx_booking_items_booking ON booking_items (booking_id);

CREATE TABLE payments (
                          id           UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                          booking_id   UUID           NOT NULL REFERENCES bookings (id),
                          user_id      UUID           NOT NULL REFERENCES users (id),
                          amount       NUMERIC(19, 4) NOT NULL,
                          currency     CHAR(3)        NOT NULL DEFAULT 'INR',
                          status       VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
                          provider     VARCHAR(30)    NOT NULL,
                          provider_ref VARCHAR(100),
                          failure_code VARCHAR(50),
                          version      BIGINT         NOT NULL DEFAULT 0,
                          created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
                          updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
                          CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'REFUNDED')),
                          CONSTRAINT ck_payments_amount CHECK (amount > 0)
);
-- At most one payment "in play" per booking.
CREATE UNIQUE INDEX uq_payments_one_live_per_booking
    ON payments (booking_id) WHERE status IN ('PENDING', 'UNKNOWN', 'SUCCEEDED');
-- The provider's reference is unique per provider (when we have one).
CREATE UNIQUE INDEX uq_payments_provider_ref ON payments (provider, provider_ref) WHERE provider_ref IS NOT NULL;
CREATE INDEX idx_payments_user_created ON payments (user_id, created_at DESC);
-- Recovery job: only the (tiny) set of unresolved payments is ever scanned.
CREATE INDEX idx_payments_recoverable ON payments (updated_at) WHERE status IN ('PENDING', 'UNKNOWN');