CREATE TABLE modl_staff_credentials (
    email VARCHAR(254) NOT NULL PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
) WITHOUT ROWID;

CREATE TABLE modl_staff_totp (
    email VARCHAR(254) NOT NULL PRIMARY KEY,
    totp_secret VARCHAR(64),
    last_used_step BIGINT,
    enabled_at BIGINT,
    pending_totp_secret VARCHAR(64),
    pending_expires_at BIGINT,
    updated_at BIGINT NOT NULL
) WITHOUT ROWID;

CREATE TABLE modl_staff_recovery_codes (
    email VARCHAR(254) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (email, code_hash)
) WITHOUT ROWID;

CREATE TABLE modl_password_reset_tokens (
    email VARCHAR(254) NOT NULL PRIMARY KEY,
    token_hash VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX modl_uq_password_reset_tokens_token_hash ON modl_password_reset_tokens (token_hash);
CREATE INDEX modl_idx_password_reset_tokens_expires ON modl_password_reset_tokens (expires_at);

ALTER TABLE modl_panel_sessions ADD COLUMN auth_method VARCHAR(16) NOT NULL DEFAULT 'EMAIL_CODE';
ALTER TABLE modl_panel_sessions ADD COLUMN second_factor_verified BOOLEAN NOT NULL DEFAULT FALSE;
