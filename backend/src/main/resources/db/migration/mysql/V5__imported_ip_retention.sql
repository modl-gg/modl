ALTER TABLE modl_player_ips
    ADD COLUMN retained_until_at BIGINT NULL,
    ADD INDEX modl_idx_player_ips_retained (retained_until_at);
