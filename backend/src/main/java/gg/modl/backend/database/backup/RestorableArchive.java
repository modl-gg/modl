package gg.modl.backend.database.backup;

import gg.modl.backend.database.sql.RestoreTarget;
import gg.modl.backend.database.sql.SqliteFile;
import java.io.Closeable;
import java.io.IOException;
import java.util.Set;

public final class RestorableArchive implements Closeable {
    private final RestoreTarget target;
    private final WorkDirectory work;
    private final SqliteFile verified;
    private final Set<String> newerVersionTables;
    private final TransferReport report;

    RestorableArchive(RestoreTarget target, WorkDirectory work, SqliteFile verified, Set<String> newerVersionTables,
                      TransferReport report) {
        this.target = target;
        this.work = work;
        this.verified = verified;
        this.newerVersionTables = Set.copyOf(newerVersionTables);
        this.report = report;
    }

    public TransferReport restore() throws IOException {
        verified.revokeShortLivedAccess();
        return transfer();
    }

    public TransferReport transfer() throws IOException {
        target.replaceWith(verified, newerVersionTables);
        return report;
    }

    @Override
    public void close() throws IOException {
        work.close();
    }
}
