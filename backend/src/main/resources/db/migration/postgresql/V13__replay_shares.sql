CREATE TABLE modl_replay_shares (
    id VARCHAR(36) COLLATE "C" NOT NULL PRIMARY KEY,
    replay_id VARCHAR(64) COLLATE "C" NOT NULL,
    token_hash VARCHAR(64) COLLATE "C" NOT NULL,
    created_by VARCHAR(64) COLLATE "C" NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT,
    revoked_at BIGINT,
    view_count BIGINT NOT NULL DEFAULT 0,
    last_viewed_at BIGINT,
    CONSTRAINT modl_fk_replay_shares_replay FOREIGN KEY (replay_id) REFERENCES modl_replays (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX modl_uq_replay_shares_token_hash ON modl_replay_shares (token_hash);
CREATE INDEX modl_idx_replay_shares_replay ON modl_replay_shares (replay_id, created_at, id);
