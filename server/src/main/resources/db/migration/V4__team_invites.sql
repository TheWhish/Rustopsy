ALTER TABLE teams DROP COLUMN invite_code;

CREATE TABLE team_invites (
    team_id    BIGINT      NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    invitee_id BIGINT      NOT NULL REFERENCES users (steam_id) ON DELETE CASCADE,
    inviter_id BIGINT      NOT NULL REFERENCES users (steam_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, invitee_id)
);

CREATE INDEX team_invites_invitee_id ON team_invites (invitee_id);
