package gg.modl.backend.hostedimport.mapping;

import java.util.Arrays;
import java.util.Optional;

public enum ArchiveCollection {
    CHAT_LOGS("chat_logs"),
    COMMAND_LOGS("command_logs"),
    HOMEPAGE_CARDS("homepagecards"),
    ARTICLES("knowledgebasearticles"),
    CATEGORIES("knowledgebasecategories"),
    LOGS("logs"),
    PLAYERS("players"),
    REPLAYS("replays"),
    SETTINGS("settings"),
    STAFF_ROLES("staffroles"),
    STAFF("staffs"),
    STORAGE_FILES("storage_files"),
    TICKETS("tickets");

    private final String archiveName;

    ArchiveCollection(String archiveName) {
        this.archiveName = archiveName;
    }

    public String archiveName() {
        return archiveName;
    }

    public static Optional<ArchiveCollection> fromArchiveName(String name) {
        return Arrays.stream(values()).filter(collection -> collection.archiveName.equals(name)).findFirst();
    }
}
