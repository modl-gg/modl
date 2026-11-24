package gg.modl.backend.infrastructure.rest;

import java.util.List;

public final class RESTMappingV1 {
    public static final String V1 = "/v1";
    public static final String PREFIX_PANEL = V1 + "/panel";
    public static final String PREFIX_PUBLIC = V1 + "/public";
    public static final String PREFIX_MINECRAFT = V1 + "/minecraft";
    public static final String HEALTH = V1 + "/health";
    private static final String PLAYERS = "/players";
    public static final String PANEL_PLAYERS = PREFIX_PANEL + PLAYERS;
    public static final String PUBLIC_PLAYERS = PREFIX_PUBLIC + PLAYERS;
    public static final String MINECRAFT_PLAYERS = PREFIX_MINECRAFT + PLAYERS;
    private static final String AUTH = "/auth";
    public static final String PANEL_AUTH = PREFIX_PANEL + AUTH;
    public static final String PANEL_AUTH_SEND_EMAIL_CODE = PANEL_AUTH + "/send-email-code";
    public static final String PANEL_AUTH_VERIFY_EMAIL_CODE = PANEL_AUTH + "/verify-email-code";
    public static final String PANEL_AUTH_LOGIN_LINK = PANEL_AUTH + "/link";
    public static final String PANEL_AUTH_LOGOUT = PANEL_AUTH + "/logout";
    public static final String PANEL_AUTH_EMAIL_CHANGE_SEND_CODE = PANEL_AUTH + "/email/send-code";
    public static final String PANEL_AUTH_WEBAUTHN = PANEL_AUTH + "/webauthn";
    public static final String PANEL_AUTH_WEBAUTHN_LOGIN = PANEL_AUTH_WEBAUTHN + "/login";
    public static final String PANEL_AUTH_WEBAUTHN_REGISTER_OPTIONS = PANEL_AUTH_WEBAUTHN + "/register/options";
    public static final String PANEL_AUTH_WEBAUTHN_REGISTER_VERIFY = PANEL_AUTH_WEBAUTHN + "/register/verify";
    public static final String PANEL_AUTH_ME = PANEL_AUTH + "/me";
    public static final String PANEL_AUTH_SESSIONS = PANEL_AUTH + "/sessions";
    public static final String PANEL_AUTH_EMAIL = PANEL_AUTH + "/email";
    public static final String PANEL_AUTH_SECURITY = PANEL_AUTH + "/security";
    public static final String PANEL_AUTH_PASSWORD = PANEL_AUTH + "/password";
    public static final String PANEL_AUTH_PASSWORD_LOGIN = PANEL_AUTH_PASSWORD + "/login";
    public static final String PANEL_AUTH_PASSWORD_RESET = PANEL_AUTH_PASSWORD + "/reset";
    public static final String PANEL_AUTH_PASSWORD_RESET_INSPECT = PANEL_AUTH_PASSWORD_RESET + "/inspect";
    public static final String PANEL_AUTH_SECOND_FACTOR_SIGN_IN = PANEL_AUTH + "/sign-in/second-factor";
    public static final String PANEL_AUTH_TOTP = PANEL_AUTH + "/totp";
    public static final String PANEL_AUTH_TOTP_CONFIRM = PANEL_AUTH_TOTP + "/confirm";
    public static final String PANEL_AUTH_TOTP_DISABLE = PANEL_AUTH_TOTP + "/disable";
    public static final String PANEL_AUTH_TOTP_RECOVERY_CODES = PANEL_AUTH_TOTP + "/recovery-codes";
    public static final List<String> PANEL_AUTH_CREDENTIAL_EXCHANGES = List.of(
        PANEL_AUTH_VERIFY_EMAIL_CODE,
        PANEL_AUTH_LOGIN_LINK,
        PANEL_AUTH_WEBAUTHN_LOGIN + "/**"
    );
    public static final List<String> PANEL_AUTH_ANONYMOUS_ENDPOINTS = List.of(
        PANEL_AUTH_SEND_EMAIL_CODE,
        PANEL_AUTH_VERIFY_EMAIL_CODE,
        PANEL_AUTH_LOGIN_LINK,
        PANEL_AUTH_LOGOUT,
        PANEL_AUTH_WEBAUTHN_LOGIN + "/**",
        PANEL_AUTH_PASSWORD_LOGIN,
        PANEL_AUTH_PASSWORD_RESET,
        PANEL_AUTH_PASSWORD_RESET_INSPECT,
        PANEL_AUTH_SECOND_FACTOR_SIGN_IN
    );
    private static final String SETTINGS = "/settings";
    public static final String PANEL_SETTINGS = PREFIX_PANEL + SETTINGS;
    public static final String PANEL_SETTINGS_SIGN_IN = PANEL_SETTINGS + "/sign-in";
    public static final String PANEL_SETTINGS_SERVER_KEYS = PANEL_SETTINGS + "/server-keys";
    public static final String PANEL_SETTINGS_PUBLIC_THEME = PANEL_SETTINGS + "/public-theme";
    public static final String PANEL_SETTINGS_PUBLIC_CSS = PANEL_SETTINGS + "/public-css";
    public static final String PANEL_SETTINGS_STORAGE = PANEL_SETTINGS + "/storage";
    public static final String PANEL_SETTINGS_DATABASE = PANEL_SETTINGS + "/database";
    public static final String PANEL_SETTINGS_STATUS = PANEL_SETTINGS + "/status";
    public static final String PANEL_SETTINGS_RETENTION_LIMITS = PANEL_SETTINGS + "/data-retention/limits";
    public static final String MINECRAFT_STATUS = PREFIX_MINECRAFT + "/status";
    public static final String PANEL_STAFF_SECURITY = PREFIX_PANEL + "/staff-security";
    public static final String PUBLIC_SETTINGS = PREFIX_PUBLIC + SETTINGS;
    public static final String PUBLIC_THEME_STYLESHEET = PREFIX_PUBLIC + "/theme.css";
    private static final String TICKETS = "/tickets";
    public static final String PANEL_TICKETS = PREFIX_PANEL + TICKETS;
    public static final String PUBLIC_TICKETS = PREFIX_PUBLIC + TICKETS;
    public static final String MINECRAFT_TICKETS = PREFIX_MINECRAFT + TICKETS;
    private static final String TICKET_SUBSCRIPTIONS = "/ticket-subscriptions";
    public static final String PANEL_TICKET_SUBSCRIPTIONS = PREFIX_PANEL + TICKET_SUBSCRIPTIONS;
    private static final String STAFF = "/staff";
    public static final String PANEL_STAFF = PREFIX_PANEL + STAFF;
    public static final String PUBLIC_STAFF = PREFIX_PUBLIC + STAFF;
    public static final String MINECRAFT_STAFF = PREFIX_MINECRAFT + STAFF;
    private static final String ROLES = "/roles";
    public static final String PANEL_ROLES = PREFIX_PANEL + ROLES;
    public static final String MINECRAFT_ROLES = PREFIX_MINECRAFT + ROLES;
    private static final String ANALYTICS = "/analytics";
    public static final String PANEL_ANALYTICS = PREFIX_PANEL + ANALYTICS;
    private static final String AUDIT = "/audit";
    public static final String PANEL_AUDIT = PREFIX_PANEL + AUDIT;
    private static final String DASHBOARD = "/dashboard";
    public static final String PANEL_DASHBOARD = PREFIX_PANEL + DASHBOARD;
    public static final String MINECRAFT_DASHBOARD = PREFIX_MINECRAFT + DASHBOARD;
    private static final String MEDIA = "/media";
    public static final String PANEL_MEDIA = PREFIX_PANEL + MEDIA;
    public static final String PUBLIC_MEDIA = PREFIX_PUBLIC + MEDIA;
    private static final String STORAGE = "/storage";
    private static final String KNOWLEDGEBASE = "/knowledgebase";
    public static final String PANEL_KNOWLEDGEBASE = PREFIX_PANEL + KNOWLEDGEBASE;
    public static final String PUBLIC_KNOWLEDGEBASE = PREFIX_PUBLIC + KNOWLEDGEBASE;
    private static final String HOMEPAGE_CARDS = "/homepage-cards";
    public static final String PANEL_HOMEPAGE_CARDS = PREFIX_PANEL + HOMEPAGE_CARDS;
    public static final String PUBLIC_HOMEPAGE_CARDS = PREFIX_PUBLIC + HOMEPAGE_CARDS;
    private static final String APPEALS = "/appeals";
    public static final String PANEL_APPEALS = PREFIX_PANEL + APPEALS;
    public static final String PUBLIC_APPEALS = PREFIX_PUBLIC + APPEALS;
    private static final String LOGS = "/logs";
    public static final String PANEL_LOGS = PREFIX_PANEL + LOGS;
    private static final String MIGRATION = "/migration";
    public static final String MINECRAFT_MIGRATION = PREFIX_MINECRAFT + MIGRATION;
    private static final String PUNISHMENTS = "/punishments";
    public static final String MINECRAFT_PUNISHMENTS = PREFIX_MINECRAFT + PUNISHMENTS;
    private static final String PUNISHMENT = "/punishment";
    public static final String PUBLIC_PUNISHMENT = PREFIX_PUBLIC + PUNISHMENT;
    private static final String NOTIFICATIONS = "/notifications";
    public static final String MINECRAFT_NOTIFICATIONS = PREFIX_MINECRAFT + NOTIFICATIONS;
    private static final String REPORTS = "/reports";
    public static final String MINECRAFT_REPORTS = PREFIX_MINECRAFT + REPORTS;
    private static final String EVIDENCE_UPLOAD = "/evidence-upload";
    public static final String PUBLIC_EVIDENCE_UPLOAD = PREFIX_PUBLIC + EVIDENCE_UPLOAD;
    private static final String SETUP = "/setup";
    public static final String PUBLIC_SETUP = PREFIX_PUBLIC + SETUP;
    public static final String PANEL_SETUP = PREFIX_PANEL + SETUP;
    public static final String PANEL_SETUP_IMPORT = PANEL_SETUP + "/import";
    private static final String SERVERS = "/servers";
    public static final String PANEL_SERVERS = PREFIX_PANEL + SERVERS;
    private static final String UPDATES = "/updates";
    public static final String PANEL_UPDATES = PREFIX_PANEL + UPDATES;
    private static final String ACCOUNT = "/account";
    public static final String PANEL_ACCOUNT = PREFIX_PANEL + ACCOUNT;
    private static final String PANEL_LINK = "/panel";
    public static final String MINECRAFT_PANEL = PREFIX_MINECRAFT + PANEL_LINK;
    public static final String STORAGE_OBJECTS = V1 + STORAGE + "/objects";
    private static final String REALTIME = "/realtime";
    public static final String REALTIME_WS = V1 + REALTIME + "/ws";
    private static final String REPLAYS = "/replays";
    public static final String MINECRAFT_REPLAYS = PREFIX_MINECRAFT + REPLAYS;
    public static final String PUBLIC_REPLAYS = PREFIX_PUBLIC + REPLAYS;
    public static final String PANEL_REPLAYS = PREFIX_PANEL + REPLAYS;

    private RESTMappingV1() {
    }
}
