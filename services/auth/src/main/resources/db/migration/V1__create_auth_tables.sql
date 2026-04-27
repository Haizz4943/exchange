-- ============================================================
-- Auth Service — initial schema
-- ============================================================

CREATE TABLE users (
    id                   UUID         PRIMARY KEY,
    email                VARCHAR(254) NOT NULL,
    email_normalized     VARCHAR(254) NOT NULL,
    email_verified       BOOLEAN      NOT NULL DEFAULT TRUE,
    status               VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    external_provider    VARCHAR(40)  NOT NULL DEFAULT 'local',
    external_subject_id  VARCHAR(128) NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_email_normalized UNIQUE (email_normalized),
    CONSTRAINT uq_users_external_identity UNIQUE (external_provider, external_subject_id),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED'))
);

CREATE TABLE credentials (
    user_id         UUID         PRIMARY KEY REFERENCES users(id),
    password_hash   VARCHAR(255) NOT NULL,
    hash_algorithm  VARCHAR(20)  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_credentials_algorithm CHECK (hash_algorithm IN ('BCRYPT', 'ARGON2ID'))
);

CREATE TABLE sessions (
    id                   UUID         PRIMARY KEY,
    user_id              UUID         NOT NULL REFERENCES users(id),
    refresh_token_hash   VARCHAR(255) NOT NULL,
    issued_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ  NOT NULL,
    revoked_at           TIMESTAMPTZ  NULL,
    last_used_at         TIMESTAMPTZ  NULL,
    user_agent           VARCHAR(500) NULL,
    ip_address           VARCHAR(45)  NULL
);

CREATE INDEX ix_sessions_user_active   ON sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_sessions_token_hash    ON sessions (refresh_token_hash);
CREATE INDEX ix_sessions_user_id       ON sessions (user_id);

CREATE TABLE login_attempts (
    id             UUID         PRIMARY KEY,
    email          VARCHAR(254) NULL,
    ip_address     VARCHAR(45)  NOT NULL,
    success        BOOLEAN      NOT NULL,
    attempted_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_login_attempts_email ON login_attempts (email, attempted_at DESC);
CREATE INDEX ix_login_attempts_ip    ON login_attempts (ip_address, attempted_at DESC);

CREATE TABLE auth_outbox (
    id            UUID        PRIMARY KEY,
    event_type    VARCHAR(40) NOT NULL,
    aggregate_id  UUID        NOT NULL,
    payload_json  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ NULL,
    attempts      INT         NOT NULL DEFAULT 0
);

CREATE INDEX ix_auth_outbox_unpublished ON auth_outbox (created_at) WHERE published_at IS NULL;
