CREATE TABLE teams (
    id          BIGSERIAL PRIMARY KEY,
    leader_id   BIGINT      NOT NULL REFERENCES users (steam_id),
    invite_code TEXT        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE team_members (
    steam_id  BIGINT PRIMARY KEY REFERENCES users (steam_id) ON DELETE CASCADE,
    team_id   BIGINT      NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX team_members_team_id ON team_members (team_id);
