CREATE TABLE venues (
                        id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                        name       VARCHAR(200) NOT NULL,
                        city       VARCHAR(100) NOT NULL,
                        created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Physical seats of a venue. Reused across many events.
CREATE TABLE seats (
                       id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                       venue_id    UUID        NOT NULL REFERENCES venues (id) ON DELETE CASCADE,
                       section     VARCHAR(30) NOT NULL,
                       row_label   VARCHAR(10) NOT NULL,
                       seat_number INTEGER     NOT NULL,
                       CONSTRAINT uq_seats_position UNIQUE (venue_id, section, row_label, seat_number),
                       CONSTRAINT ck_seats_number CHECK (seat_number > 0)
);

CREATE TABLE events (
                        id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                        venue_id           UUID         NOT NULL REFERENCES venues (id),
                        organizer_id       UUID         NOT NULL REFERENCES users (id),
                        name               VARCHAR(200) NOT NULL,
                        description        TEXT,
                        starts_at          TIMESTAMPTZ  NOT NULL,
                        sale_starts_at     TIMESTAMPTZ  NOT NULL,
                        sale_ends_at       TIMESTAMPTZ  NOT NULL,
                        max_seats_per_user INTEGER      NOT NULL DEFAULT 4,
                        status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
                        created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        CONSTRAINT ck_events_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ON_SALE', 'SOLD_OUT', 'CANCELLED', 'COMPLETED')),
                        CONSTRAINT ck_events_sale_window CHECK (sale_ends_at > sale_starts_at),
                        CONSTRAINT ck_events_max_seats CHECK (max_seats_per_user BETWEEN 1 AND 10)
);

CREATE INDEX idx_events_organizer ON events (organizer_id);
CREATE INDEX idx_events_status_sale_start ON events (status, sale_starts_at);