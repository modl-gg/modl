package gg.modl.backend.databasemove.service;

import gg.modl.backend.database.backup.TransferReport;
import gg.modl.backend.database.selection.DatabaseLocation;
import gg.modl.backend.database.selection.MoveFailure;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record DatabaseMove(
    DatabaseMovePhase phase,
    DatabaseLocation target,
    @Nullable String errorCode,
    @Nullable String error,
    @Nullable String errorDetail,
    long copiedTables,
    long copiedRows,
    @Nullable Instant startedAt,
    @Nullable Instant updatedAt
) {
    static DatabaseMove started(DatabaseLocation target, Instant now) {
        return new DatabaseMove(DatabaseMovePhase.CHECKING, target, null, null, null, 0, 0, now, now);
    }

    static DatabaseMove failedEarlier(DatabaseLocation target, MoveFailure failure) {
        return new DatabaseMove(DatabaseMovePhase.FAILED, target, failure.code(), failure.message(), failure.detail(), 0, 0, null,
            null);
    }

    public boolean active() {
        return phase != DatabaseMovePhase.FAILED;
    }

    DatabaseMove at(DatabaseMovePhase next, Instant now) {
        return new DatabaseMove(next, target, null, null, null, copiedTables, copiedRows, startedAt, now);
    }

    DatabaseMove copied(TransferReport report, Instant now) {
        return new DatabaseMove(DatabaseMovePhase.VERIFYING, target, null, null, null, report.tables(), report.rows(), startedAt,
            now);
    }

    DatabaseMove failed(MoveFailure failure, Instant now) {
        return new DatabaseMove(DatabaseMovePhase.FAILED, target, failure.code(), failure.message(), failure.detail(), copiedTables,
            copiedRows, startedAt, now);
    }
}
