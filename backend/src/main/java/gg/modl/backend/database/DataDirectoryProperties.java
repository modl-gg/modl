package gg.modl.backend.database;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modl")
public record DataDirectoryProperties(@Nullable String dataDir) {
    public static final String DEFAULT_DATA_DIR = "./data";

    private static final String DATABASE_DIRECTORY = "database";
    private static final String STORAGE_DIRECTORY = "storage";
    private static final String BACKUPS_DIRECTORY = "backups";
    private static final String TEMPORARY_DIRECTORY = "tmp";

    public DataDirectoryProperties {
        dataDir = dataDir == null || dataDir.isBlank() ? DEFAULT_DATA_DIR : dataDir.trim();
    }

    public Path root() {
        return Path.of(dataDir).toAbsolutePath().normalize();
    }

    public Path database() {
        return root().resolve(DATABASE_DIRECTORY);
    }

    public Path storage() {
        return root().resolve(STORAGE_DIRECTORY);
    }

    public Path backups() {
        return root().resolve(BACKUPS_DIRECTORY);
    }

    public Path temporary() {
        return root().resolve(TEMPORARY_DIRECTORY);
    }
}
