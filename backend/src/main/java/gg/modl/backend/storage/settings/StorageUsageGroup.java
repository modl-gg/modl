package gg.modl.backend.storage.settings;

import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.StorageFolders;
import gg.modl.backend.storage.StorageNamespace;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public enum StorageUsageGroup {
    REPLAYS(List.of(StorageFolders.REPLAYS + "/")),
    EVIDENCE(List.of()),
    TICKETS(List.of()),
    ARTICLES(List.of()),
    ICONS(List.of(StorageFolders.ICON_PREFIX, StorageFolders.THEME_BACKGROUND + "/")),
    OTHER(List.of());

    private final List<String> foldersOutsideMedia;

    StorageUsageGroup(List<String> foldersOutsideMedia) {
        this.foldersOutsideMedia = foldersOutsideMedia;
    }

    static StorageUsageGroup of(MediaCategory category) {
        return switch (category) {
            case EVIDENCE -> EVIDENCE;
            case TICKET, APPEAL -> TICKETS;
            case ARTICLE -> ARTICLES;
            case SERVER_ICON -> ICONS;
            case REPLAY -> REPLAYS;
        };
    }

    List<String> keyPrefixes() {
        Stream<String> mediaFolders = Arrays.stream(MediaCategory.values())
            .filter(category -> of(category) == this)
            .flatMap(category -> category.keyFolders().stream())
            .map(folder -> folder + "/");
        return Stream.concat(mediaFolders, foldersOutsideMedia.stream())
            .map(folder -> StorageNamespace.KEY_PREFIX + folder)
            .toList();
    }
}
