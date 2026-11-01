-- Money is captured but owed back to the customer (cancellation, or a charge that arrived after we gave up).
ALTER TABLE payments DROP CONSTRAINT ck_payments_status;
ALTER TABLE payments ADD CONSTRAINT ck_payments_status
    CHECK (status IN ('PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'REFUND_PENDING', 'REFUNDED'));

CREATE TABLE refunds (
                         id           UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                         payment_id   UUID           NOT NULL REFERENCES payments (id),
                         booking_id   UUID           NOT NULL REFERENCES bookings (id),
                         user_id      UUID           NOT NULL REFERENCES users (id),
                         amount       NUMERIC(19, 4) NOT NULL,
                         currency     CHAR(3)        NOT NULL DEFAULT 'INR',
                         reason       VARCHAR(30)    NOT NULL,
                         status       VARCHAR(12)    NOT NULL DEFAULT 'REQUESTED',
                         attempts     INTEGER        NOT NULL DEFAULT 0,
                         provider_ref VARCHAR(100),
                         failure_code VARCHAR(50),
                         created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
                         updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
                         CONSTRAINT uq_refunds_payment UNIQUE (payment_id),     -- a payment is refunded at most once, ever
                         CONSTRAINT ck_refunds_status CHECK (status IN ('REQUESTED', 'UNKNOWN', 'SUCCEEDED', 'FAILED')),
                         CONSTRAINT ck_refunds_reason CHECK (reason IN ('BOOKING_CANCELLED', 'LATE_PAYMENT_SUCCESS')),
                         CONSTRAINT ck_refunds_amount CHECK (amount > 0)
);
CREATE INDEX idx_refunds_booking ON refunds (booking_id);
-- The recovery job scans only unfinished refunds.
CREATE INDEX idx_refunds_recoverable ON refunds (updated_at) WHERE status IN ('REQUESTED', 'UNKNOWN', 'FAILED');

CREATE TABLE webhook_events (
                                id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                provider          VARCHAR(30)  NOT NULL,
                                provider_event_id VARCHAR(100) NOT NULL,
                                event_type        VARCHAR(50)  NOT NULL,
                                payload           TEXT         NOT NULL,       -- raw body, kept for audit and debugging
                                received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                CONSTRAINT uq_webhook_provider_event UNIQUE (provider, provider_event_id)
);