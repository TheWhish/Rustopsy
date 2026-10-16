CREATE TABLE pending_logins (
    id             TEXT PRIMARY KEY,
    state          TEXT        NOT NULL,
    code_challenge TEXT        NOT NULL,
    port           INTEGER     NOT NULL,
    steam_id       BIGINT,
    code_hash      BYTEA UNIQUE,
    expires_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE sessions (
    token_hash   BYTEA PRIMARY KEY,
    steam_id     BIGINT      NOT NULL REFERENCES users (steam_id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX sessions_steam_id ON sessions (steam_id);
