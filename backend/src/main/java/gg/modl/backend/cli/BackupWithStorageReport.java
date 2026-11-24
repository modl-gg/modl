package gg.modl.backend.cli;

import gg.modl.backend.backup.ArchivedStorage;
import gg.modl.backend.database.backup.TransferReport;

record BackupWithStorageReport(TransferReport database, ArchivedStorage storage) {

    String describe() {
        return database.describe() + ", " + storage.describe();
    }
}
