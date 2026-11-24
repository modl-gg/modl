CREATE TABLE modl_storage_transfers (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    source_backend VARCHAR(8) NOT NULL CHECK (source_backend IN ('LOCAL', 'S3')),
    target_backend VARCHAR(8) NOT NULL CHECK (target_backend IN ('LOCAL', 'S3')),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RUNNING', 'FAILED', 'DONE')),
    copied_objects BIGINT NOT NULL DEFAULT 0,
    copied_bytes BIGINT NOT NULL DEFAULT 0,
    skipped_objects BIGINT NOT NULL DEFAULT 0,
    failed_objects BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    started_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    finished_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE INDEX modl_idx_storage_transfers_started ON modl_storage_transfers (started_at, id);
