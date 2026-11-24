package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.backup.BackupFiles;
import gg.modl.backend.backup.BackupSeries;
import gg.modl.backend.backup.BackupSeries.WrittenBackup;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.sql.BackupSource;
import gg.modl.update.ArtifactFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class UpgradeBackups {
    public static final String PREFIX = "pre-update-";
    public static final int KEEP = 3;

    private static final Pattern FILE_NAME = Pattern.compile(Pattern.quote(PREFIX) + "[0-9A-Za-z._-]{1,64}\\.zip");

    private final DatabaseBackup backup;
    private final BackupSource source;
    private final BackupSeries series;
    private final Path directory;

    public UpgradeBackups(DatabaseBackup backup, BackupSource source, DataDirectoryProperties dataDirectory, Clock clock) {
        this.backup = backup;
        this.source = source;
        this.directory = dataDirectory.backups();
        this.series = new BackupSeries(new BackupFiles(directory, clock), BackupDiskSpace.SYSTEM, PREFIX, KEEP);
    }

    public UpgradeBackup write(String fromVersion, String toVersion) throws IOException {
        UpgradeLabel label = new UpgradeLabel(fromVersion, toVersion, source.tablesAnUpgradeCreates());
        WrittenBackup written = backup.writeInto(series, backup.preferredKind(), label);
        Path archive = written.archive();
        return new UpgradeBackup(archive.getFileName().toString(), ArtifactFiles.sha256(archive), archive);
    }

    public long estimatedBytes() {
        return backup.requiredBytes(backup.preferredKind());
    }

    public Path directory() {
        return directory;
    }

    public Path resolve(String fileName) throws IOException {
        if (!FILE_NAME.matcher(fileName).matches()) {
            throw new IOException("Not a pre-update backup name: " + fileName);
        }
        return directory.resolve(fileName);
    }

    public record UpgradeBackup(String fileName, String sha256, Path path) {
    }
}
