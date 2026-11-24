ALTER TABLE modl_punishments ADD COLUMN issuer_kind VARCHAR(16) COLLATE "C" NOT NULL DEFAULT 'UNKNOWN';
UPDATE modl_punishments SET issuer_kind = CASE
    WHEN issuer_id IS NOT NULL THEN 'STAFF'
    WHEN type_ordinal = 4 THEN 'AUTOMATED'
    WHEN issuer_name_lower IN ('ai moderator', 'system') THEN 'AUTOMATED'
    WHEN issuer_name IS NULL OR issuer_name_lower = 'console' THEN 'CONSOLE'
    ELSE 'STAFF'
END;
CREATE INDEX modl_idx_punishments_issued_id ON modl_punishments (issued_at, id);
