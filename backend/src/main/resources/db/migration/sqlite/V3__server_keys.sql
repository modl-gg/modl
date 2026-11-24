CREATE TABLE modl_server_keys (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    name_lower VARCHAR(64) NOT NULL,
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('FULL', 'BEHIND_PROXY')),
    allow_proxy_commands BOOLEAN NOT NULL DEFAULT FALSE,
    key_hash VARCHAR(64) NOT NULL,
    key_hint VARCHAR(16) NOT NULL,
    created_by_email VARCHAR(254),
    created_at BIGINT NOT NULL,
    last_seen_at BIGINT
);
CREATE UNIQUE INDEX modl_uq_server_keys_key_hash ON modl_server_keys (key_hash);
CREATE UNIQUE INDEX modl_uq_server_keys_name_lower ON modl_server_keys (name_lower);
