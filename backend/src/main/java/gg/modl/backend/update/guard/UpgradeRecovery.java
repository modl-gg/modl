package gg.modl.backend.update.guard;

import gg.modl.backend.database.backup.DatabaseRestore;
import gg.modl.backend.database.backup.RestorableArchive;
import gg.modl.backend.database.backup.UpgradeBackups;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.util.FailureDescriptions;
import gg.modl.update.ArtifactFiles;
import gg.modl.update.UpdateTransaction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UpgradeRecovery {
    private final DatabaseRestore restore;
    private final UpgradeBackups backups;
    private final OperatorNotice notice;

    public void restore(UpdateTransaction transaction) {
        String backup = transaction.backup();
        String backupSha256 = transaction.backupSha256();
        if (backup == null || backupSha256 == null) {
            throw new UpgradeRefusedException("updates/pending.json names no pre-update backup, so the database cannot be restored.");
        }
        Path archive;
        try {
            archive = backups.resolve(backup);
        } catch (IOException e) {
            throw new UpgradeRefusedException("updates/pending.json names an invalid backup (" + backup + ").", e);
        }
        requireIntact(archive, backupSha256);
        notice.publish("Restoring the database from {}, the backup made before updating modl from {} to {}", archive,
            transaction.from(), transaction.to());
        try (RestorableArchive restorable = restore.openUpgradeBackup(archive)) {
            restorable.restore();
        } catch (IOException | RuntimeException e) {
            throw new UpgradeRefusedException("The database could not be restored from " + archive + " ("
                + FailureDescriptions.describe(e) + "). Nothing else was changed; fix the cause and start modl again.", e);
        }
    }

    private static void requireIntact(Path archive, String expectedSha256) {
        try {
            if (!Files.isRegularFile(archive) || !ArtifactFiles.sha256(archive).equals(expectedSha256)) {
                throw new UpgradeRefusedException("The pre-update backup " + archive + " is missing or damaged, so the database cannot"
                    + " be restored automatically. Restore your own backup.");
            }
        } catch (IOException e) {
            throw new UpgradeRefusedException("The pre-update backup " + archive + " could not be read (" + e.getMessage() + ").", e);
        }
    }
}
