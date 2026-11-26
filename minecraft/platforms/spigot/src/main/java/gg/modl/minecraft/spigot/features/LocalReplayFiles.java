package gg.modl.minecraft.spigot.features;

import gg.modl.minecraft.core.util.OwnerOnlyFiles;
import gg.modl.minecraft.replay.recording.ReplayOutput;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class LocalReplayFiles implements ReplayOutput {
    static final long LEFTOVER_TTL_MILLIS = 24L * 60L * 60_000L;
    static final long PARTIAL_TTL_MILLIS = 60L * 60_000L;
    static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
    static final long SWEEP_DELAY_SECONDS = 1L;
    static final long SWEEP_PERIOD_SECONDS = 5L * 60L;
    private static final String DIRECTORY = "replays";
    private static final String PARTIAL_SUFFIX = ".partial";

    private final Path directory;

    LocalReplayFiles(Path directory) {
        this.directory = directory;
    }

    static LocalReplayFiles of(JavaPlugin plugin) {
        return new LocalReplayFiles(plugin.getDataFolder().toPath().resolve(DIRECTORY));
    }

    @Override
    public Path directory() {
        return directory;
    }

    @Override
    public OutputStream create(Path file) throws IOException {
        OwnerOnlyFiles.createDirectories(directory);
        return OwnerOnlyFiles.createFile(file);
    }

    int sweep(long nowMillis) {
        File[] listed = directory.toFile().listFiles(File::isFile);
        if (listed == null) return 0;
        List<File> kept = new ArrayList<>();
        int deleted = 0;
        for (File file : listed) {
            if (expired(file, nowMillis)) {
                if (file.delete()) deleted++;
            } else {
                kept.add(file);
            }
        }
        long total = 0;
        for (File file : kept) total += file.length();
        File[] oldestFirst = kept.toArray(new File[0]);
        Arrays.sort(oldestFirst, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        for (File file : oldestFirst) {
            if (total <= MAX_TOTAL_BYTES) break;
            if (nowMillis - file.lastModified() <= PARTIAL_TTL_MILLIS) continue;
            long size = file.length();
            if (file.delete()) {
                total -= size;
                deleted++;
            }
        }
        return deleted;
    }

    private static boolean expired(File file, long nowMillis) {
        long ttl = file.getName().endsWith(PARTIAL_SUFFIX) ? PARTIAL_TTL_MILLIS : LEFTOVER_TTL_MILLIS;
        return nowMillis - file.lastModified() > ttl;
    }
}
