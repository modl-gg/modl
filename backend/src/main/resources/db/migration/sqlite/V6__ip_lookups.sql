CREATE TABLE modl_ip_lookups (
    ip VARCHAR(45) NOT NULL PRIMARY KEY,
    provider VARCHAR(16) NOT NULL,
    country VARCHAR(100),
    region VARCHAR(128),
    asn VARCHAR(128),
    vpn VARCHAR(8) NOT NULL CHECK (vpn IN ('YES', 'NO', 'UNKNOWN')),
    is_hosting BOOLEAN NOT NULL DEFAULT FALSE,
    looked_up_at BIGINT NOT NULL
);
CREATE INDEX modl_idx_ip_lookups_looked_up ON modl_ip_lookups (looked_up_at, ip);
ALTER TABLE modl_player_links ADD COLUMN shared_clean_address BOOLEAN NOT NULL DEFAULT FALSE;
