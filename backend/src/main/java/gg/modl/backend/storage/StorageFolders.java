package gg.modl.backend.storage;

import gg.modl.backend.storage.service.StorageKeyUtils;

public final class StorageFolders {
    public static final String REPLAYS = "replays";
    public static final String ICON_PREFIX = "icons_";
    public static final String THEME_BACKGROUND = "theme_background";

    private StorageFolders() {
    }

    public static boolean servedByStorageUrl(String key) {
        return StorageKeySegments.isCanonical(key) && !REPLAYS.equalsIgnoreCase(StorageKeyUtils.folderOf(key));
    }

    public static String icon(String iconName) {
        return ICON_PREFIX + iconName;
    }
}
