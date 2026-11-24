ALTER TABLE modl_player_ips ADD COLUMN retained_until_at BIGINT;
CREATE INDEX modl_idx_player_ips_retained ON modl_player_ips (retained_until_at);
