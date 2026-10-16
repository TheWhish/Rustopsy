CREATE TABLE users (
    steam_id     BIGINT PRIMARY KEY,
    name         TEXT        NOT NULL,
    avatar_url   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
