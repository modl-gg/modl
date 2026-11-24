CREATE TABLE modl_instance (
    id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    server_name VARCHAR(100) NOT NULL,
    admin_email VARCHAR(254),
    api_key VARCHAR(255),
    public_url VARCHAR(2048),
    setup_started_at BIGINT,
    setup_owner_signed_in_at BIGINT,
    setup_completed_at BIGINT,
    staff_permissions_updated_at BIGINT,
    punishment_types_updated_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE modl_setup_steps (
    step VARCHAR(32) NOT NULL PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'DONE', 'SKIPPED')),
    updated_at BIGINT NOT NULL
) WITHOUT ROWID;

CREATE TABLE modl_instance_secrets (
    id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    auth_code_hash_secret VARCHAR(255) NOT NULL,
    storage_signing_key VARCHAR(255) NOT NULL,
    setup_code_hash VARCHAR(255),
    setup_code_expires_at BIGINT,
    setup_code_issued_at BIGINT,
    setup_code_failed_attempts INTEGER NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL
);

CREATE TABLE modl_settings (
    type VARCHAR(64) NOT NULL PRIMARY KEY,
    data_json TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL
);

CREATE TABLE modl_players (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    minecraft_uuid VARCHAR(36) NOT NULL,
    username VARCHAR(100) NOT NULL,
    username_lower VARCHAR(200) NOT NULL,
    is_online BOOLEAN NOT NULL DEFAULT FALSE,
    first_join_at BIGINT,
    last_login_at BIGINT,
    last_logout_at BIGINT,
    last_server_name VARCHAR(64),
    total_playtime_seconds BIGINT NOT NULL DEFAULT 0,
    last_skin_hash VARCHAR(128),
    last_linked_update_at BIGINT,
    extra_json TEXT,
    created_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX modl_uq_players_minecraft_uuid ON modl_players (minecraft_uuid);
CREATE INDEX modl_idx_players_online ON modl_players (is_online, last_server_name);
CREATE INDEX modl_idx_players_first_join ON modl_players (first_join_at);

CREATE TABLE modl_player_usernames (
    player_id VARCHAR(36) NOT NULL,
    username_lower VARCHAR(200) NOT NULL,
    username VARCHAR(100) NOT NULL,
    first_seen_at BIGINT,
    last_seen_at BIGINT NOT NULL,
    PRIMARY KEY (player_id, username_lower),
    CONSTRAINT modl_fk_player_usernames_player FOREIGN KEY (player_id) REFERENCES modl_players (id) ON DELETE CASCADE
) WITHOUT ROWID;
CREATE INDEX modl_idx_player_usernames_lookup ON modl_player_usernames (username_lower, player_id);

CREATE TABLE modl_player_ips (
    player_id VARCHAR(36) NOT NULL,
    ip VARCHAR(45) NOT NULL,
    country VARCHAR(100),
    region VARCHAR(128),
    asn VARCHAR(128),
    is_proxy BOOLEAN NOT NULL DEFAULT FALSE,
    is_hosting BOOLEAN NOT NULL DEFAULT FALSE,
    first_seen_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    login_count BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (player_id, ip),
    CONSTRAINT modl_fk_player_ips_player FOREIGN KEY (player_id) REFERENCES modl_players (id) ON DELETE CASCADE
) WITHOUT ROWID;
CREATE INDEX modl_idx_player_ips_ip ON modl_player_ips (ip, player_id);

CREATE TABLE modl_player_notes (
    player_id VARCHAR(36) NOT NULL,
    seq INTEGER NOT NULL,
    id VARCHAR(100) NOT NULL,
    body TEXT NOT NULL,
    kind VARCHAR(8) NOT NULL DEFAULT 'NOTE' CHECK (kind IN ('NOTE', 'WARNING')),
    issuer_name VARCHAR(100),
    issuer_id VARCHAR(64),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (player_id, seq),
    CONSTRAINT modl_fk_player_notes_player FOREIGN KEY (player_id) REFERENCES modl_players (id) ON DELETE CASCADE
) WITHOUT ROWID;

CREATE TABLE modl_player_links (
    player_id VARCHAR(36) NOT NULL,
    linked_player_id VARCHAR(36) NOT NULL,
    linked_at BIGINT NOT NULL,
    PRIMARY KEY (player_id, linked_player_id),
    CONSTRAINT modl_fk_player_links_player FOREIGN KEY (player_id) REFERENCES modl_players (id) ON DELETE CASCADE,
    CONSTRAINT modl_fk_player_links_linked FOREIGN KEY (linked_player_id) REFERENCES modl_players (id) ON DELETE CASCADE
) WITHOUT ROWID;
CREATE INDEX modl_idx_player_links_linked ON modl_player_links (linked_player_id);

CREATE TABLE modl_player_notifications (
    player_id VARCHAR(36) NOT NULL,
    id VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    PRIMARY KEY (player_id, id),
    CONSTRAINT modl_fk_player_notifications_player FOREIGN KEY (player_id) REFERENCES modl_players (id) ON DELETE CASCADE
) WITHOUT ROWID;
CREATE INDEX modl_idx_player_notifications_expires ON modl_player_notifications (expires_at);

CREATE TABLE modl_punishments (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    player_id VARCHAR(36) NOT NULL,
    type_ordinal INTEGER NOT NULL,
    issuer_name VARCHAR(100),
    issuer_name_lower VARCHAR(200),
    issuer_id VARCHAR(64),
    issued_at BIGINT NOT NULL,
    started_at BIGINT,
    status VARCHAR(32),
    duration_ms BIGINT,
    reason TEXT,
    severity VARCHAR(32),
    offense_level VARCHAR(32),
    enforcement_category VARCHAR(8) NOT NULL CHECK (enforcement_category IN ('BAN', 'MUTE', 'NONE')),
    alt_blocking BOOLEAN NOT NULL DEFAULT FALSE,
    wipe_after_expiry BOOLEAN NOT NULL DEFAULT FALSE,
    stat_wipe_completed_at BIGINT,
    linked_ban_id VARCHAR(64),
    linked_ban_parent_uuid VARCHAR(36),
    blocked_name VARCHAR(100),
    blocked_skin VARCHAR(128),
    appeal_outcome VARCHAR(32),
    appeal_ticket_id VARCHAR(64),
    extra_json TEXT,
    active_until_at BIGINT NOT NULL,
    modified_at BIGINT,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT modl_fk_punishments_player FOREIGN KEY (player_id) REFERENCES modl_players (id) ON DELETE CASCADE
);
CREATE INDEX modl_idx_punishments_player ON modl_punishments (player_id, issued_at);
CREATE INDEX modl_idx_punishments_issued ON modl_punishments (issued_at, active_until_at);
CREATE INDEX modl_idx_punishments_issuer_id ON modl_punishments (issuer_id, issued_at);
CREATE INDEX modl_idx_punishments_issuer_name ON modl_punishments (issuer_name_lower, issued_at);
CREATE INDEX modl_idx_punishments_active ON modl_punishments (active_until_at, enforcement_category);
CREATE INDEX modl_idx_punishments_linked_ban ON modl_punishments (linked_ban_id);

CREATE TABLE modl_punishment_modifications (
    punishment_id VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    id VARCHAR(100) NOT NULL,
    type VARCHAR(64) NOT NULL,
    issuer_name VARCHAR(254),
    issuer_id VARCHAR(100),
    reason TEXT NOT NULL,
    effective_duration_ms BIGINT,
    appeal_ticket_id VARCHAR(100),
    data_json TEXT,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (punishment_id, seq),
    CONSTRAINT modl_fk_punishment_modifications_punishment FOREIGN KEY (punishment_id) REFERENCES modl_punishments (id) ON DELETE CASCADE
) WITHOUT ROWID;

CREATE TABLE modl_punishment_notes (
    punishment_id VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    id VARCHAR(36) NOT NULL,
    body TEXT NOT NULL,
    issuer_name VARCHAR(254),
    issuer_id VARCHAR(64),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (punishment_id, seq),
    CONSTRAINT modl_fk_punishment_notes_punishment FOREIGN KEY (punishment_id) REFERENCES modl_punishments (id) ON DELETE CASCADE
) WITHOUT ROWID;

CREATE TABLE modl_punishment_evidence (
    punishment_id VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    type VARCHAR(100) NOT NULL,
    body TEXT,
    url TEXT,
    uploaded_by VARCHAR(100),
    uploaded_by_id VARCHAR(100),
    file_name VARCHAR(500),
    file_type VARCHAR(128),
    file_size BIGINT,
    uploaded_at BIGINT NOT NULL,
    PRIMARY KEY (punishment_id, seq),
    CONSTRAINT modl_fk_punishment_evidence_punishment FOREIGN KEY (punishment_id) REFERENCES modl_punishments (id) ON DELETE CASCADE
) WITHOUT ROWID;

CREATE TABLE modl_punishment_tickets (
    punishment_id VARCHAR(64) NOT NULL,
    ticket_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (punishment_id, ticket_id),
    CONSTRAINT modl_fk_punishment_tickets_punishment FOREIGN KEY (punishment_id) REFERENCES modl_punishments (id) ON DELETE CASCADE
) WITHOUT ROWID;
CREATE INDEX modl_idx_punishment_tickets_ticket ON modl_punishment_tickets (ticket_id);

CREATE TABLE modl_tickets (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    type VARCHAR(16) NOT NULL,
    subject TEXT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('unfinished', 'open', 'closed')),
    priority VARCHAR(8) NOT NULL DEFAULT 'normal' CHECK (priority IN ('low', 'normal', 'high')),
    creator_uuid VARCHAR(36),
    creator_name VARCHAR(100),
    creator_avatar TEXT,
    reported_player_name VARCHAR(100),
    reported_player_uuid VARCHAR(36),
    is_locked BOOLEAN NOT NULL DEFAULT FALSE,
    is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    email_auth_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    appeal_workflow_status VARCHAR(32),
    appeal_punishment_id VARCHAR(64),
    replay_url TEXT,
    replay_id VARCHAR(64),
    public_access_key VARCHAR(128) NOT NULL,
    form_data_json TEXT,
    data_json TEXT,
    chat_messages_json TEXT,
    ai_analysis_json TEXT,
    reply_count INTEGER NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX modl_idx_tickets_created ON modl_tickets (created_at);
CREATE INDEX modl_idx_tickets_updated ON modl_tickets (updated_at);
CREATE INDEX modl_idx_tickets_status_created ON modl_tickets (status, created_at);
CREATE INDEX modl_idx_tickets_type_created ON modl_tickets (type, created_at);
CREATE INDEX modl_idx_tickets_locked_created ON modl_tickets (is_locked, created_at);
CREATE INDEX modl_idx_tickets_creator_created ON modl_tickets (creator_uuid, created_at);
CREATE INDEX modl_idx_tickets_reported_created ON modl_tickets (reported_player_uuid, created_at);
CREATE INDEX modl_idx_tickets_appeal_punishment ON modl_tickets (appeal_punishment_id);
CREATE INDEX modl_idx_tickets_replay ON modl_tickets (replay_id);

CREATE TABLE modl_ticket_replies (
    ticket_id VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    id VARCHAR(36) NOT NULL,
    author_name VARCHAR(254),
    author_name_lower VARCHAR(508),
    avatar TEXT,
    content TEXT,
    type VARCHAR(32),
    is_staff BOOLEAN NOT NULL DEFAULT FALSE,
    action VARCHAR(64),
    attachments_json TEXT,
    creator_identifier VARCHAR(256),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (ticket_id, seq),
    CONSTRAINT modl_fk_ticket_replies_ticket FOREIGN KEY (ticket_id) REFERENCES modl_tickets (id) ON DELETE CASCADE
);
CREATE INDEX modl_idx_ticket_replies_staff ON modl_ticket_replies (is_staff, created_at);
CREATE INDEX modl_idx_ticket_replies_author ON modl_ticket_replies (author_name_lower, created_at);

CREATE TABLE modl_ticket_notes (
    ticket_id VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    body TEXT NOT NULL,
    issuer_name VARCHAR(100),
    issuer_avatar TEXT,
    attachments_json TEXT,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (ticket_id, seq),
    CONSTRAINT modl_fk_ticket_notes_ticket FOREIGN KEY (ticket_id) REFERENCES modl_tickets (id) ON DELETE CASCADE
) WITHOUT ROWID;

CREATE TABLE modl_ticket_tags (
    ticket_id VARCHAR(64) NOT NULL,
    tag VARCHAR(64) NOT NULL,
    PRIMARY KEY (ticket_id, tag),
    CONSTRAINT modl_fk_ticket_tags_ticket FOREIGN KEY (ticket_id) REFERENCES modl_tickets (id) ON DELETE CASCADE
) WITHOUT ROWID;
CREATE INDEX modl_idx_ticket_tags_tag ON modl_ticket_tags (tag, ticket_id);

CREATE TABLE modl_ticket_assignees (
    ticket_id VARCHAR(64) NOT NULL,
    assignee VARCHAR(512) NOT NULL,
    PRIMARY KEY (ticket_id, assignee),
    CONSTRAINT modl_fk_ticket_assignees_ticket FOREIGN KEY (ticket_id) REFERENCES modl_tickets (id) ON DELETE CASCADE
) WITHOUT ROWID;
CREATE INDEX modl_idx_ticket_assignees_assignee ON modl_ticket_assignees (assignee, ticket_id);

CREATE TABLE modl_ticket_verifications (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    ticket_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64),
    code_hash VARCHAR(255),
    email VARCHAR(254),
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    expires_at BIGINT NOT NULL,
    CONSTRAINT modl_fk_ticket_verifications_ticket FOREIGN KEY (ticket_id) REFERENCES modl_tickets (id) ON DELETE CASCADE
);
CREATE INDEX modl_idx_ticket_verifications_ticket ON modl_ticket_verifications (ticket_id);
CREATE INDEX modl_idx_ticket_verifications_expires ON modl_ticket_verifications (expires_at);

CREATE TABLE modl_staff_roles (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    name_lower VARCHAR(64) NOT NULL,
    description TEXT,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 999,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX modl_uq_staff_roles_name_lower ON modl_staff_roles (name_lower);

CREATE TABLE modl_staff_role_permissions (
    role_id VARCHAR(64) NOT NULL,
    permission VARCHAR(128) NOT NULL,
    PRIMARY KEY (role_id, permission),
    CONSTRAINT modl_fk_staff_role_permissions_role FOREIGN KEY (role_id) REFERENCES modl_staff_roles (id) ON DELETE CASCADE
) WITHOUT ROWID;

CREATE TABLE modl_staff (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    username VARCHAR(64) NOT NULL,
    role_id VARCHAR(64),
    assigned_minecraft_uuid VARCHAR(36),
    assigned_minecraft_username VARCHAR(100),
    language VARCHAR(16) NOT NULL DEFAULT 'en',
    date_format VARCHAR(32) NOT NULL DEFAULT 'MMM D, YYYY',
    ticket_subscription_settings_json TEXT,
    two_factor_token_hash VARCHAR(255),
    two_factor_token_ip VARCHAR(64),
    two_factor_token_created_at BIGINT,
    two_factor_pending_delivery BOOLEAN NOT NULL DEFAULT FALSE,
    two_factor_session_ip VARCHAR(64),
    two_factor_session_expires_at BIGINT,
    last_seen_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX modl_uq_staff_email ON modl_staff (email);
CREATE UNIQUE INDEX modl_uq_staff_username ON modl_staff (username);
CREATE UNIQUE INDEX modl_uq_staff_assigned_minecraft_uuid ON modl_staff (assigned_minecraft_uuid);
CREATE UNIQUE INDEX modl_uq_staff_two_factor_token_hash ON modl_staff (two_factor_token_hash);
CREATE INDEX modl_idx_staff_role ON modl_staff (role_id);

CREATE TABLE modl_staff_ticket_subscriptions (
    staff_id VARCHAR(36) NOT NULL,
    ticket_id VARCHAR(64) NOT NULL,
    subscribed_at BIGINT NOT NULL,
    last_read_at BIGINT,
    PRIMARY KEY (staff_id, ticket_id),
    CONSTRAINT modl_fk_staff_ticket_subscriptions_staff FOREIGN KEY (staff_id) REFERENCES modl_staff (id) ON DELETE CASCADE,
    CONSTRAINT modl_fk_staff_ticket_subscriptions_ticket FOREIGN KEY (ticket_id) REFERENCES modl_tickets (id) ON DELETE CASCADE
) WITHOUT ROWID;
CREATE INDEX modl_idx_staff_ticket_subscriptions_ticket ON modl_staff_ticket_subscriptions (ticket_id);

CREATE TABLE modl_invitations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    role_id VARCHAR(64),
    token_hash VARCHAR(255) NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX modl_uq_invitations_token_hash ON modl_invitations (token_hash);
CREATE INDEX modl_idx_invitations_email ON modl_invitations (email);
CREATE INDEX modl_idx_invitations_expires ON modl_invitations (expires_at);

CREATE TABLE modl_panel_sessions (
    id_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    session_key_hash VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX modl_idx_panel_sessions_email ON modl_panel_sessions (email);
CREATE INDEX modl_idx_panel_sessions_expires ON modl_panel_sessions (expires_at);

CREATE TABLE modl_auth_codes (
    email VARCHAR(254) NOT NULL PRIMARY KEY,
    code_hash VARCHAR(255),
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    issued_at BIGINT,
    code_expires_at BIGINT,
    recent_failures INTEGER NOT NULL DEFAULT 0,
    failure_window_started_at BIGINT,
    expires_at BIGINT NOT NULL
);
CREATE INDEX modl_idx_auth_codes_expires ON modl_auth_codes (expires_at);

CREATE TABLE modl_webauthn_credentials (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    credential_id VARCHAR(1400) NOT NULL,
    public_key_cose BLOB NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    user_handle VARCHAR(255),
    name VARCHAR(255),
    created_at BIGINT NOT NULL,
    last_used_at BIGINT
);
CREATE UNIQUE INDEX modl_uq_webauthn_credentials_credential_id ON modl_webauthn_credentials (credential_id);
CREATE INDEX modl_idx_webauthn_credentials_email ON modl_webauthn_credentials (email);
CREATE INDEX modl_idx_webauthn_credentials_user_handle ON modl_webauthn_credentials (user_handle);

CREATE TABLE modl_webauthn_challenges (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    challenge_json TEXT NOT NULL,
    email VARCHAR(254),
    expires_at BIGINT NOT NULL
);
CREATE INDEX modl_idx_webauthn_challenges_expires ON modl_webauthn_challenges (expires_at);

CREATE TABLE modl_panel_login_tokens (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    token_hash VARCHAR(255) NOT NULL,
    identity_email VARCHAR(254) NOT NULL,
    minecraft_uuid VARCHAR(36),
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX modl_uq_panel_login_tokens_token_hash ON modl_panel_login_tokens (token_hash);
CREATE INDEX modl_idx_panel_login_tokens_identity ON modl_panel_login_tokens (identity_email);
CREATE INDEX modl_idx_panel_login_tokens_expires ON modl_panel_login_tokens (expires_at);

CREATE TABLE modl_minecraft_link_codes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    code_hash VARCHAR(255) NOT NULL,
    identity_email VARCHAR(254) NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX modl_uq_minecraft_link_codes_code_hash ON modl_minecraft_link_codes (code_hash);
CREATE UNIQUE INDEX modl_uq_minecraft_link_codes_identity ON modl_minecraft_link_codes (identity_email);
CREATE INDEX modl_idx_minecraft_link_codes_expires ON modl_minecraft_link_codes (expires_at);

CREATE TABLE modl_chat_logs (
    id INTEGER NOT NULL PRIMARY KEY,
    player_id VARCHAR(36) NOT NULL,
    username VARCHAR(64),
    message TEXT NOT NULL,
    server_name VARCHAR(64) NOT NULL DEFAULT '',
    sent_at BIGINT NOT NULL
);
CREATE INDEX modl_idx_chat_logs_player ON modl_chat_logs (player_id, sent_at);

CREATE TABLE modl_command_logs (
    id INTEGER NOT NULL PRIMARY KEY,
    player_id VARCHAR(36) NOT NULL,
    username VARCHAR(64),
    command TEXT NOT NULL,
    server_name VARCHAR(64) NOT NULL DEFAULT '',
    sent_at BIGINT NOT NULL
);
CREATE INDEX modl_idx_command_logs_player ON modl_command_logs (player_id, sent_at);

CREATE TABLE modl_audit_logs (
    id INTEGER NOT NULL PRIMARY KEY,
    level VARCHAR(16) NOT NULL,
    source VARCHAR(254) NOT NULL,
    description TEXT NOT NULL,
    metadata_json TEXT,
    can_rollback BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL
);
CREATE INDEX modl_idx_audit_logs_source ON modl_audit_logs (source, id);

CREATE TABLE modl_knowledgebase_categories (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    description TEXT,
    ordinal INTEGER NOT NULL DEFAULT 0,
    is_visible BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX modl_uq_knowledgebase_categories_slug ON modl_knowledgebase_categories (slug);

CREATE TABLE modl_knowledgebase_articles (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    category_id VARCHAR(36) NOT NULL,
    title VARCHAR(256) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    content TEXT,
    ordinal INTEGER NOT NULL DEFAULT 0,
    is_visible BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT modl_fk_knowledgebase_articles_category FOREIGN KEY (category_id) REFERENCES modl_knowledgebase_categories (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX modl_uq_knowledgebase_articles_slug ON modl_knowledgebase_articles (slug);
CREATE INDEX modl_idx_knowledgebase_articles_category ON modl_knowledgebase_articles (category_id, ordinal);

CREATE TABLE modl_homepage_cards (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    icon VARCHAR(128),
    icon_color VARCHAR(32),
    action_type VARCHAR(64),
    action_url TEXT,
    action_button_text VARCHAR(255),
    category_id VARCHAR(36),
    background_color VARCHAR(32),
    ordinal INTEGER NOT NULL DEFAULT 0,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT modl_fk_homepage_cards_category FOREIGN KEY (category_id) REFERENCES modl_knowledgebase_categories (id) ON DELETE SET NULL
);
CREATE INDEX modl_idx_homepage_cards_category ON modl_homepage_cards (category_id);

CREATE TABLE modl_import_jobs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    type VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    progress_message TEXT,
    records_processed INTEGER,
    records_skipped INTEGER,
    total_records INTEGER,
    error_message TEXT,
    started_from VARCHAR(64),
    started_address VARCHAR(45),
    end_reason VARCHAR(32),
    export_failed INTEGER,
    export_skipped INTEGER,
    scoped_punishments VARCHAR(16),
    ip_history VARCHAR(16),
    started_at BIGINT,
    last_progress_at BIGINT,
    completed_at BIGINT
);
CREATE INDEX modl_idx_import_jobs_status ON modl_import_jobs (status, started_at);

CREATE TABLE modl_replays (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    mc_version VARCHAR(32),
    target_uuid VARCHAR(36),
    target_name VARCHAR(64),
    file_size BIGINT NOT NULL DEFAULT 0,
    storage_key VARCHAR(512),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'COMPLETE', 'FAILED')),
    labels_json TEXT,
    created_at BIGINT NOT NULL
);
CREATE INDEX modl_idx_replays_target ON modl_replays (target_uuid, created_at);
CREATE INDEX modl_idx_replays_status_created ON modl_replays (status, created_at, id);
CREATE INDEX modl_idx_replays_storage_key ON modl_replays (storage_key);

CREATE TABLE modl_storage_files (
    storage_key VARCHAR(512) NOT NULL PRIMARY KEY,
    file_name VARCHAR(255),
    size_bytes BIGINT NOT NULL,
    content_type VARCHAR(127),
    category VARCHAR(32),
    unattached_expires_at BIGINT,
    created_at BIGINT NOT NULL,
    origin VARCHAR(16) NOT NULL
);
CREATE INDEX modl_idx_storage_files_unattached ON modl_storage_files (unattached_expires_at);
CREATE INDEX modl_idx_storage_files_origin_created ON modl_storage_files (origin, created_at);

CREATE TABLE modl_storage_reservations (
    storage_key VARCHAR(512) NOT NULL PRIMARY KEY,
    reserved_bytes BIGINT NOT NULL,
    is_landed BOOLEAN NOT NULL DEFAULT FALSE,
    settle_after_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    origin VARCHAR(16) NOT NULL
);
CREATE INDEX modl_idx_storage_reservations_settle ON modl_storage_reservations (settle_after_at);

CREATE TABLE modl_evidence_upload_tokens (
    token_hash VARCHAR(255) NOT NULL PRIMARY KEY,
    punishment_id VARCHAR(64) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    issuer_name VARCHAR(100),
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX modl_idx_evidence_upload_tokens_expires ON modl_evidence_upload_tokens (expires_at);
