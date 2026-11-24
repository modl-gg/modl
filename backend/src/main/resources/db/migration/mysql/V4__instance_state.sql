CREATE TABLE modl_instance_state (
    id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    last_run_version VARCHAR(64) NOT NULL,
    version_changed_at BIGINT NOT NULL,
    checks_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    include_prereleases BOOLEAN NOT NULL DEFAULT FALSE,
    last_check_at BIGINT,
    last_check_outcome VARCHAR(24),
    check_failures INTEGER NOT NULL DEFAULT 0,
    next_check_at BIGINT,
    stable_release_json MEDIUMTEXT,
    stable_etag VARCHAR(255),
    prerelease_release_json MEDIUMTEXT,
    prerelease_etag VARCHAR(255),
    notified_version VARCHAR(64),
    dismissed_version VARCHAR(64),
    dismissed_by VARCHAR(254)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
