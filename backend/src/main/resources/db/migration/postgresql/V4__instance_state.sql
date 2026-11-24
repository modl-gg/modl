CREATE TABLE modl_instance_state (
    id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    last_run_version VARCHAR(64) COLLATE "C" NOT NULL,
    version_changed_at BIGINT NOT NULL,
    checks_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    include_prereleases BOOLEAN NOT NULL DEFAULT FALSE,
    last_check_at BIGINT,
    last_check_outcome VARCHAR(24) COLLATE "C",
    check_failures INTEGER NOT NULL DEFAULT 0,
    next_check_at BIGINT,
    stable_release_json TEXT COLLATE "C",
    stable_etag VARCHAR(255) COLLATE "C",
    prerelease_release_json TEXT COLLATE "C",
    prerelease_etag VARCHAR(255) COLLATE "C",
    notified_version VARCHAR(64) COLLATE "C",
    dismissed_version VARCHAR(64) COLLATE "C",
    dismissed_by VARCHAR(254) COLLATE "C"
);
