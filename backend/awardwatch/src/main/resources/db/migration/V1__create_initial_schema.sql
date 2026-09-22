CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(320) NOT NULL,
    password_hash   TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email_not_blank CHECK (btrim(email) <> ''),
    CONSTRAINT ck_app_user_password_hash_not_blank CHECK (btrim(password_hash) <> '')
);

CREATE TABLE watch (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    origin          CHAR(3) NOT NULL,
    destination     CHAR(3) NOT NULL,
    date_from       DATE NOT NULL,
    date_to         DATE NOT NULL,
    cabins          TEXT[] NOT NULL,
    programs        TEXT[],
    max_mileage     INTEGER,
    min_seats       SMALLINT NOT NULL DEFAULT 1,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_watch_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_watch_origin CHECK (origin ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_watch_destination CHECK (destination ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_watch_distinct_airports CHECK (origin <> destination),
    CONSTRAINT ck_watch_date_range CHECK (date_from <= date_to),
    CONSTRAINT ck_watch_cabins_not_empty CHECK (cardinality(cabins) > 0),
    CONSTRAINT ck_watch_cabins_valid CHECK (cabins <@ ARRAY['Y', 'W', 'J', 'F']::TEXT[]),
    CONSTRAINT ck_watch_programs_not_empty CHECK (programs IS NULL OR cardinality(programs) > 0),
    CONSTRAINT ck_watch_max_mileage_positive CHECK (max_mileage IS NULL OR max_mileage > 0),
    CONSTRAINT ck_watch_min_seats_positive CHECK (min_seats > 0)
);

CREATE INDEX ix_watch_user_id ON watch (user_id);

CREATE TABLE snapshot (
    id              BIGSERIAL PRIMARY KEY,
    origin          CHAR(3) NOT NULL,
    destination     CHAR(3) NOT NULL,
    date_from       DATE NOT NULL,
    date_to         DATE NOT NULL,
    program         TEXT NOT NULL,
    observed_at     TIMESTAMPTZ NOT NULL,
    api_calls_used  SMALLINT NOT NULL,
    succeeded       BOOLEAN NOT NULL,
    source          TEXT NOT NULL,

    CONSTRAINT uq_snapshot_id_program UNIQUE (id, program),
    CONSTRAINT ck_snapshot_origin CHECK (origin ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_snapshot_destination CHECK (destination ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_snapshot_distinct_airports CHECK (origin <> destination),
    CONSTRAINT ck_snapshot_date_range CHECK (date_from <= date_to),
    CONSTRAINT ck_snapshot_program_not_blank CHECK (btrim(program) <> ''),
    CONSTRAINT ck_snapshot_api_calls_nonnegative CHECK (api_calls_used >= 0),
    CONSTRAINT ck_snapshot_source_not_blank CHECK (btrim(source) <> '')
);

CREATE INDEX ix_snapshot_route_program_observed_at
    ON snapshot (origin, destination, program, observed_at DESC, id DESC);

CREATE INDEX ix_snapshot_successful_route_program_observed_at
    ON snapshot (origin, destination, program, observed_at DESC, id DESC)
    WHERE succeeded;

CREATE TABLE availability_entry (
    id              BIGSERIAL PRIMARY KEY,
    snapshot_id     BIGINT NOT NULL,
    departure_date  DATE NOT NULL,
    program         TEXT NOT NULL,
    cabin           CHAR(1) NOT NULL,
    mileage_cost    INTEGER NOT NULL,
    seats_remaining SMALLINT,
    nonstop         BOOLEAN NOT NULL,
    refreshed_at    TIMESTAMPTZ,

    CONSTRAINT fk_availability_entry_snapshot
        FOREIGN KEY (snapshot_id, program) REFERENCES snapshot (id, program) ON DELETE CASCADE,
    CONSTRAINT uq_availability_entry_award
        UNIQUE (snapshot_id, departure_date, program, cabin, nonstop),
    CONSTRAINT ck_availability_entry_program_not_blank CHECK (btrim(program) <> ''),
    CONSTRAINT ck_availability_entry_cabin CHECK (cabin IN ('Y', 'W', 'J', 'F')),
    CONSTRAINT ck_availability_entry_mileage_positive CHECK (mileage_cost > 0),
    CONSTRAINT ck_availability_entry_seats_nonnegative
        CHECK (seats_remaining IS NULL OR seats_remaining >= 0)
);

CREATE INDEX ix_availability_entry_snapshot_id ON availability_entry (snapshot_id);

CREATE TABLE crawl_state (
    origin              CHAR(3) NOT NULL,
    destination         CHAR(3) NOT NULL,
    program             TEXT NOT NULL,
    date_from            DATE NOT NULL,
    date_to              DATE NOT NULL,
    last_crawled_at      TIMESTAMPTZ,
    consecutive_empty   SMALLINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_crawl_state
        PRIMARY KEY (origin, destination, program, date_from, date_to),
    CONSTRAINT ck_crawl_state_origin CHECK (origin ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_crawl_state_destination CHECK (destination ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_crawl_state_distinct_airports CHECK (origin <> destination),
    CONSTRAINT ck_crawl_state_program_not_blank CHECK (btrim(program) <> ''),
    CONSTRAINT ck_crawl_state_date_range CHECK (date_from <= date_to),
    CONSTRAINT ck_crawl_state_consecutive_empty_nonnegative CHECK (consecutive_empty >= 0)
);

CREATE TABLE alert_event (
    id              BIGSERIAL PRIMARY KEY,
    watch_id        BIGINT NOT NULL,
    entry_id        BIGINT NOT NULL,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    channel         TEXT NOT NULL,

    CONSTRAINT fk_alert_event_watch FOREIGN KEY (watch_id) REFERENCES watch (id) ON DELETE CASCADE,
    CONSTRAINT fk_alert_event_entry
        FOREIGN KEY (entry_id) REFERENCES availability_entry (id) ON DELETE CASCADE,
    CONSTRAINT ck_alert_event_channel_not_blank CHECK (btrim(channel) <> '')
);

CREATE INDEX ix_alert_event_watch_sent_at ON alert_event (watch_id, sent_at DESC);
CREATE INDEX ix_alert_event_entry_id ON alert_event (entry_id);
