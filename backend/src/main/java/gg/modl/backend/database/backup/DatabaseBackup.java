package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupSeries;
import gg.modl.backend.backup.BackupSeries.WrittenBackup;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;

public interface DatabaseBackup {
    ArchiveKind preferredKind();

    long requiredBytes(ArchiveKind kind);

    TransferReport write(ArchiveKind kind, ZipOutputStream zip, @Nullable UpgradeLabel label) throws IOException;

    TransferReport writeMoveCopy(ZipOutputStream zip) throws IOException;

    default TransferReport write(ArchiveKind kind, ZipOutputStream zip) throws IOException {
        return write(kind, zip, null);
    }

    default WrittenBackup writeInto(BackupSeries series, ArchiveKind kind) throws IOException {
        return writeInto(series, kind, null);
    }

    default WrittenBackup writeInto(BackupSeries series, ArchiveKind kind, @Nullable UpgradeLabel label) throws IOException {
        return series.write(requiredBytes(kind), output -> {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
                write(kind, zip, label);
            }
        });
    }
}
