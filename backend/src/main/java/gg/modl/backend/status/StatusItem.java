package gg.modl.backend.status;

public enum StatusItem {
    VERSION("instance.version", StatusSection.INSTANCE, "Version"),
    DEPLOYMENT("instance.deployment", StatusSection.INSTANCE, "Runs as"),
    JAVA("instance.java", StatusSection.INSTANCE, "Java"),
    OPERATING_SYSTEM("instance.os", StatusSection.INSTANCE, "Operating system"),
    UPTIME("instance.uptime", StatusSection.INSTANCE, "Uptime"),
    SETUP("instance.setup", StatusSection.INSTANCE, "Setup"),
    PANEL_URL("network.panelUrl", StatusSection.NETWORK, "Panel URL"),
    LISTEN_PORT("network.port", StatusSection.NETWORK, "Listening on"),
    PROXY_HEADERS("network.proxyHeaders", StatusSection.NETWORK, "Trusts proxy headers"),
    REACHABILITY("network.reachability", StatusSection.NETWORK, "Reachable at the panel URL"),
    TUNNEL("network.tunnel", StatusSection.NETWORK, "Cloudflare Tunnel"),
    DATABASE_TYPE("database.type", StatusSection.DATABASE, "Type"),
    STORAGE_BACKEND("storage.backend", StatusSection.STORAGE, "Uploads stored in"),
    STORAGE_USED("storage.used", StatusSection.STORAGE, "Uploads"),
    STORAGE_TRANSFER("storage.transfer", StatusSection.STORAGE, "File copy"),
    DISK_FREE("storage.diskFree", StatusSection.STORAGE, "Free disk space"),
    BACKUP_LATEST("backups.latest", StatusSection.BACKUPS, "Latest nightly backup"),
    BACKUP_COUNT("backups.count", StatusSection.BACKUPS, "Nightly backups kept"),
    BACKUP_SCHEDULE("backups.schedule", StatusSection.BACKUPS, "Schedule"),
    EMAIL_DELIVERY("email.delivery", StatusSection.EMAIL, "Delivery"),
    UPDATE_CHECKS("updates.checks", StatusSection.UPDATES, "Update checks"),
    UPDATE_AVAILABLE("updates.available", StatusSection.UPDATES, "Available update"),
    UPDATE_LAST_FAILURE("updates.lastFailure", StatusSection.UPDATES, "Last failed update");

    private final String key;
    private final StatusSection section;
    private final String label;

    StatusItem(String key, StatusSection section, String label) {
        this.key = key;
        this.section = section;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public StatusSection section() {
        return section;
    }

    public String label() {
        return label;
    }

    public StatusFact ok(String value) {
        return new StatusFact(this, value, StatusLevel.OK);
    }

    public StatusFact warning(String value) {
        return new StatusFact(this, value, StatusLevel.WARNING);
    }

    public StatusFact problem(String value) {
        return new StatusFact(this, value, StatusLevel.PROBLEM);
    }
}
