package gg.modl.minecraft.spigot.features;

import java.io.File;

final class LocalReplayFiles {
    private LocalReplayFiles() {
    }

    static int deleteOlderThan(File directory, long maxAgeMillis, long nowMillis) {
        File[] files = directory.listFiles();
        if (files == null) return 0;
        int deleted = 0;
        for (File file : files) {
            if (file.isFile() && nowMillis - file.lastModified() > maxAgeMillis && file.delete()) deleted++;
        }
        return deleted;
    }
}
