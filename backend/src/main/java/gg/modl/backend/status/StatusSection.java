package gg.modl.backend.status;

public enum StatusSection {
    INSTANCE("Instance"),
    NETWORK("Network"),
    DATABASE("Database"),
    STORAGE("Storage"),
    BACKUPS("Backups"),
    EMAIL("Email"),
    UPDATES("Updates");

    private final String title;

    StatusSection(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
