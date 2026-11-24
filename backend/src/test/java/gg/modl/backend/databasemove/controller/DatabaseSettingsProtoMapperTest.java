package gg.modl.backend.databasemove.controller;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.selection.DatabaseLocation;
import gg.modl.backend.database.sql.DurabilityWarning;
import gg.modl.backend.databasemove.service.DatabaseSettingsView;
import gg.modl.backend.databasemove.service.DatabaseTestResult;
import gg.modl.backend.databasemove.service.TargetWarning;
import gg.modl.backend.update.install.InstanceRestart.Mode;
import gg.modl.proto.modl.v1.DatabaseRestartMode;
import gg.modl.proto.modl.v1.DatabaseWarning;
import gg.modl.proto.modl.v1.DatabaseWarningKind;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DatabaseSettingsProtoMapperTest {
    @Test
    void warningsTravelAsKindsWithTheirValues() {
        DatabaseTestResult result = new DatabaseTestResult(true, "OK", "The database is ready", "", "PostgreSQL 17.2", List.of(
            new TargetWarning.Durability(new DurabilityWarning(DurabilityWarning.Kind.SETTING, "fsync", "off", "on")),
            new TargetWarning.Durability(new DurabilityWarning(DurabilityWarning.Kind.NOT_STRICT, "", "", "")),
            new TargetWarning.NotEncrypted("db.example.com"), new TargetWarning.NoNightlyBackups()), false);

        assertThat(DatabaseSettingsProtoMapper.toResponse(result).getWarningsList()).containsExactly(
            DatabaseWarning.newBuilder().setKind(DatabaseWarningKind.DATABASE_WARNING_KIND_SETTING).setSetting("fsync").setValue("off")
                .setExpected("on").build(),
            DatabaseWarning.newBuilder().setKind(DatabaseWarningKind.DATABASE_WARNING_KIND_NOT_STRICT).build(),
            DatabaseWarning.newBuilder().setKind(DatabaseWarningKind.DATABASE_WARNING_KIND_NOT_ENCRYPTED).setHost("db.example.com").build(),
            DatabaseWarning.newBuilder().setKind(DatabaseWarningKind.DATABASE_WARNING_KIND_NO_NIGHTLY_BACKUPS).build());
    }

    @Test
    void theRestartModeTellsThePanelHowTheMoveFinishes() {
        assertThat(restartModeOf(Mode.IN_PLACE)).isEqualTo(DatabaseRestartMode.DATABASE_RESTART_MODE_IN_PLACE);
        assertThat(restartModeOf(Mode.EXIT)).isEqualTo(DatabaseRestartMode.DATABASE_RESTART_MODE_EXIT);
        assertThat(restartModeOf(Mode.MANUAL)).isEqualTo(DatabaseRestartMode.DATABASE_RESTART_MODE_MANUAL);
    }

    @Test
    void theConsoleTextOfADurabilityWarningStaysTheSame() {
        assertThat(new DurabilityWarning(DurabilityWarning.Kind.SETTING, "fsync", "off", "on").describe())
            .isEqualTo("The database setting fsync is off; set it to on so a crash cannot lose acknowledged moderation actions.");
    }

    private static DatabaseRestartMode restartModeOf(Mode mode) {
        return DatabaseSettingsProtoMapper.toResponse(new DatabaseSettingsView(DatabaseLocation.BUILT_IN, Optional.empty(), false, false,
            mode, Optional.empty())).getRestartMode();
    }
}
