package gg.modl.backend.webhook;

public enum WebhookEvent {
    NEW_TICKETS("newTickets", "ticket", "tickets", true),
    NEW_PUNISHMENTS("newPunishments", "punishment", "punishments", true),
    AUDIT_LOGS("auditLogs", "audit log entry", "audit log entries", false),
    TEST("test", "test message", "test messages", false);

    private final String wireName;
    private final String singular;
    private final String plural;
    private final boolean mentionsAdmins;

    WebhookEvent(String wireName, String singular, String plural, boolean mentionsAdmins) {
        this.wireName = wireName;
        this.singular = singular;
        this.plural = plural;
        this.mentionsAdmins = mentionsAdmins;
    }

    public String wireName() {
        return wireName;
    }

    String countedAs(int count) {
        return count + " " + (count == 1 ? singular : plural);
    }

    boolean mentionsAdmins() {
        return mentionsAdmins;
    }
}
