CREATE TABLE modl_players_rebuilt (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    minecraft_uuid VARCHAR(36) NOT NULL,
    username VARCHAR(64) NOT NULL,
    username_lower VARCHAR(64) NOT NULL,
    is_online BOOLEAN NOT NULL DEFAULT FALSE,
    first_join_at BIGINT,
    last_login_at BIGINT,
    last_logout_at BIGINT,
    last_server_name VARCHAR(64),
    total_playtime_seconds BIGINT NOT NULL DEFAULT 0 CHECK (total_playtime_seconds >= 0),
    last_skin_hash VARCHAR(128),
    last_linked_update_at BIGINT,
    extra_json TEXT,
    created_at BIGINT NOT NULL
);

INSERT INTO modl_players_rebuilt (id, minecraft_uuid, username, username_lower, is_online, first_join_at, last_login_at, last_logout_at, last_server_name, total_playtime_seconds, last_skin_hash, last_linked_update_at, extra_json, created_at)
SELECT id, minecraft_uuid, username, username_lower, is_online, first_join_at, last_login_at, last_logout_at, last_server_name, total_playtime_seconds, last_skin_hash, last_linked_update_at, extra_json, created_at
FROM modl_players;

DROP TABLE modl_players;
ALTER TABLE modl_players_rebuilt RENAME TO modl_players;
CREATE UNIQUE INDEX modl_uq_players_minecraft_uuid ON modl_players (minecraft_uuid);
CREATE INDEX modl_idx_players_online ON modl_players (is_online, last_server_name);
CREATE INDEX modl_idx_players_first_join ON modl_players (first_join_at);
