package gg.modl.minecraft.core.service;

import gg.modl.minecraft.core.locale.LocaleManager;

public enum TicketKind {
    REPORT("report"),
    CHAT_REPORT("chat_report"),
    SUPPORT("support"),
    BUG("bug"),
    APPLICATION("application");

    private final String localeKey;

    TicketKind(String localeKeySuffix) {
        this.localeKey = "messages.ticket_types." + localeKeySuffix;
    }

    public String displayName(LocaleManager locale) {
        return locale.getMessage(localeKey + ".name");
    }

    public String inlineName(LocaleManager locale) {
        return locale.getMessage(localeKey + ".inline");
    }
}
