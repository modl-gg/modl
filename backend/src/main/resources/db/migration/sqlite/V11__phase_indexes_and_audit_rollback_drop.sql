CREATE INDEX modl_idx_punishments_started ON modl_punishments (started_at, issued_at, id);
CREATE INDEX modl_idx_punishments_status ON modl_punishments (status, issued_at, id);
ALTER TABLE modl_audit_logs DROP COLUMN can_rollback;
ALTER TABLE modl_homepage_cards DROP COLUMN action_button_text;
