CREATE TABLE modl_storage_transfers (
    id VARCHAR(36) COLLATE "C" NOT NULL PRIMARY KEY,
    source_backend VARCHAR(8) COLLATE "C" NOT NULL CHECK (source_backend IN ('LOCAL', 'S3')),
    target_backend VARCHAR(8) COLLATE "C" NOT NULL CHECK (target_backend IN ('LOCAL', 'S3')),
    state VARCHAR(16) COLLATE "C" NOT NULL CHECK (state IN ('RUNNING', 'FAILED', 'DONE')),
    copied_objects BIGINT NOT NULL DEFAULT 0,
    copied_bytes BIGINT NOT NULL DEFAULT 0,
    skipped_objects BIGINT NOT NULL DEFAULT 0,
    failed_objects BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) COLLATE "C",
    started_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    finished_at BIGINT
);
CREATE INDEX modl_idx_storage_transfers_started ON modl_storage_transfers (started_at, id);
