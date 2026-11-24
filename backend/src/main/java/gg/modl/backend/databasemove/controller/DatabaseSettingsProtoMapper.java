package gg.modl.backend.databasemove.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.setOptionalTimestamp;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.selection.DatabaseConnection;
import gg.modl.backend.database.selection.DatabaseLocation;
import gg.modl.backend.database.sql.DurabilityWarning;
import gg.modl.backend.databasemove.service.DatabaseMove;
import gg.modl.backend.databasemove.service.DatabaseMoveCodes;
import gg.modl.backend.databasemove.service.DatabaseMovePhase;
import gg.modl.backend.databasemove.service.DatabaseSettingsView;
import gg.modl.backend.databasemove.service.DatabaseTestResult;
import gg.modl.backend.databasemove.service.TargetWarning;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.update.install.InstanceRestart.Mode;
import gg.modl.proto.modl.v1.DatabaseConnectionInput;
import gg.modl.proto.modl.v1.DatabaseConnectionView;
import gg.modl.proto.modl.v1.DatabaseKind;
import gg.modl.proto.modl.v1.DatabaseMoveView;
import gg.modl.proto.modl.v1.DatabaseRestartMode;
import gg.modl.proto.modl.v1.DatabaseSettingsResponse;
import gg.modl.proto.modl.v1.DatabaseWarning;
import gg.modl.proto.modl.v1.DatabaseWarningKind;
import gg.modl.proto.modl.v1.TestDatabaseConnectionResponse;
import java.util.Objects;

final class DatabaseSettingsProtoMapper {
    private DatabaseSettingsProtoMapper() {
    }

    static DatabaseConnection toConnection(DatabaseConnectionInput input) {
        DatabaseDialect dialect = toDialect(input.getKind());
        try {
            return new DatabaseConnection(dialect, input.getHost(), input.getPort(), input.getDatabase(), input.getTls(),
                input.getUsername(), input.getPassword());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(Objects.requireNonNullElse(e.getMessage(), "The database connection is not valid"),
                DatabaseMoveCodes.INVALID);
        }
    }

    static DatabaseSettingsResponse toResponse(DatabaseSettingsView view) {
        DatabaseSettingsResponse.Builder response = DatabaseSettingsResponse.newBuilder()
            .setCurrent(toView(view.current()).setUsername(view.username().orElse("")).setTls(view.tls()))
            .setManaged(view.managed())
            .setRestartMode(toRestartMode(view.restartMode()));
        view.move().ifPresent(move -> response.setMove(toMove(move)));
        return response.build();
    }

    static TestDatabaseConnectionResponse toResponse(DatabaseTestResult result) {
        return TestDatabaseConnectionResponse.newBuilder()
            .setOk(result.ok())
            .setCode(result.code())
            .setMessage(result.message())
            .setDetail(result.detail())
            .setServerVersion(result.serverVersion())
            .addAllWarnings(result.warnings().stream().map(DatabaseSettingsProtoMapper::toWarning).toList())
            .setReplacesCopy(result.replacesCopy())
            .build();
    }

    private static DatabaseWarning toWarning(TargetWarning warning) {
        return switch (warning) {
            case TargetWarning.Durability durability -> toWarning(durability.warning());
            case TargetWarning.NotEncrypted notEncrypted -> DatabaseWarning.newBuilder()
                .setKind(DatabaseWarningKind.DATABASE_WARNING_KIND_NOT_ENCRYPTED)
                .setHost(notEncrypted.host())
                .build();
            case TargetWarning.NoNightlyBackups ignored -> DatabaseWarning.newBuilder()
                .setKind(DatabaseWarningKind.DATABASE_WARNING_KIND_NO_NIGHTLY_BACKUPS)
                .build();
        };
    }

    private static DatabaseWarning toWarning(DurabilityWarning warning) {
        return DatabaseWarning.newBuilder()
            .setKind(toWarningKind(warning.kind()))
            .setSetting(warning.setting())
            .setValue(warning.value())
            .setExpected(warning.expected())
            .build();
    }

    private static DatabaseWarningKind toWarningKind(DurabilityWarning.Kind kind) {
        return switch (kind) {
            case SETTING -> DatabaseWarningKind.DATABASE_WARNING_KIND_SETTING;
            case NOT_STRICT -> DatabaseWarningKind.DATABASE_WARNING_KIND_NOT_STRICT;
            case JOURNAL_MODE -> DatabaseWarningKind.DATABASE_WARNING_KIND_JOURNAL_MODE;
            case SYNCHRONOUS -> DatabaseWarningKind.DATABASE_WARNING_KIND_SYNCHRONOUS;
        };
    }

    private static DatabaseRestartMode toRestartMode(Mode mode) {
        return switch (mode) {
            case IN_PLACE -> DatabaseRestartMode.DATABASE_RESTART_MODE_IN_PLACE;
            case EXIT -> DatabaseRestartMode.DATABASE_RESTART_MODE_EXIT;
            case MANUAL -> DatabaseRestartMode.DATABASE_RESTART_MODE_MANUAL;
        };
    }

    private static DatabaseMoveView toMove(DatabaseMove move) {
        DatabaseMoveView.Builder view = DatabaseMoveView.newBuilder()
            .setPhase(toPhase(move.phase()))
            .setTarget(toView(move.target()))
            .setErrorCode(Objects.requireNonNullElse(move.errorCode(), ""))
            .setError(Objects.requireNonNullElse(move.error(), ""))
            .setErrorDetail(Objects.requireNonNullElse(move.errorDetail(), ""))
            .setCopiedTables(move.copiedTables())
            .setCopiedRows(move.copiedRows());
        setOptionalTimestamp(view::setStartedAt, move.startedAt());
        setOptionalTimestamp(view::setUpdatedAt, move.updatedAt());
        return view.build();
    }

    private static DatabaseConnectionView.Builder toView(DatabaseLocation location) {
        return DatabaseConnectionView.newBuilder()
            .setKind(toKind(location.dialect()))
            .setHost(location.host())
            .setPort(location.port())
            .setDatabase(location.database());
    }

    private static DatabaseDialect toDialect(DatabaseKind kind) {
        return switch (kind) {
            case DATABASE_KIND_SQLITE -> DatabaseDialect.SQLITE;
            case DATABASE_KIND_MYSQL -> DatabaseDialect.MYSQL;
            case DATABASE_KIND_MARIADB -> DatabaseDialect.MARIADB;
            case DATABASE_KIND_POSTGRESQL -> DatabaseDialect.POSTGRESQL;
            case DATABASE_KIND_UNSPECIFIED, UNRECOGNIZED -> throw new ValidationException("Choose a database type",
                DatabaseMoveCodes.INVALID);
        };
    }

    private static DatabaseKind toKind(DatabaseDialect dialect) {
        return switch (dialect) {
            case SQLITE -> DatabaseKind.DATABASE_KIND_SQLITE;
            case MYSQL -> DatabaseKind.DATABASE_KIND_MYSQL;
            case MARIADB -> DatabaseKind.DATABASE_KIND_MARIADB;
            case POSTGRESQL -> DatabaseKind.DATABASE_KIND_POSTGRESQL;
        };
    }

    private static gg.modl.proto.modl.v1.DatabaseMovePhase toPhase(DatabaseMovePhase phase) {
        return switch (phase) {
            case CHECKING -> gg.modl.proto.modl.v1.DatabaseMovePhase.DATABASE_MOVE_PHASE_CHECKING;
            case PAUSING_WRITES -> gg.modl.proto.modl.v1.DatabaseMovePhase.DATABASE_MOVE_PHASE_PAUSING_WRITES;
            case COPYING -> gg.modl.proto.modl.v1.DatabaseMovePhase.DATABASE_MOVE_PHASE_COPYING;
            case VERIFYING -> gg.modl.proto.modl.v1.DatabaseMovePhase.DATABASE_MOVE_PHASE_VERIFYING;
            case RESTARTING -> gg.modl.proto.modl.v1.DatabaseMovePhase.DATABASE_MOVE_PHASE_RESTARTING;
            case WAITING_FOR_RESTART -> gg.modl.proto.modl.v1.DatabaseMovePhase.DATABASE_MOVE_PHASE_WAITING_FOR_RESTART;
            case FAILED -> gg.modl.proto.modl.v1.DatabaseMovePhase.DATABASE_MOVE_PHASE_FAILED;
        };
    }
}
