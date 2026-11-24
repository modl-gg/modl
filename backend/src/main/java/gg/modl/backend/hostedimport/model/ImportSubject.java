package gg.modl.backend.hostedimport.model;

import java.util.Locale;

public enum ImportSubject {
    PLAYERS,
    PUNISHMENTS,
    NOTIFICATIONS,
    LINKS,
    TICKETS,
    REPLAYS,
    FILES,
    CHAT_LOGS,
    COMMAND_LOGS,
    AUDIT_LOGS,
    STAFF,
    ROLES,
    SETTINGS,
    CATEGORIES,
    ARTICLES,
    CARDS;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
