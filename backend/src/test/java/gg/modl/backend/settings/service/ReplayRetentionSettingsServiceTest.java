package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayRetentionSettingsServiceTest {
    private static final String TYPE = "replayRetention";
    private static final String ACTOR = "admin@example.com";

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private PanelActionAuditor auditor;
    private ReplayRetentionSettingsService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        auditor = mock(PanelActionAuditor.class);
        service = new ReplayRetentionSettingsService(kit.documents(), auditor);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void getReplayRetentionSettingsDefaultsToEnabledTenDays() {
        VersionedSettings<ReplayRetentionSettings> settings = service.getReplayRetentionSettingsState(kit.server());

        assertThat(settings.version()).isZero();
        assertThat(settings.data().isEnabled()).isTrue();
        assertThat(settings.data().getDays()).isEqualTo(10);
    }

    @Test
    void patchReplayRetentionSettingsCapsDaysAboveMaxWhenDisabled() {
        kit.store(TYPE, Map.of("enabled", false, "days", 7));

        VersionedSettings<ReplayRetentionSettings> settings = service.patchReplayRetentionSettings(kit.server(), ACTOR, 1, null, 400);

        assertThat(settings.version()).isEqualTo(2);
        assertThat(kit.stored(TYPE)).containsEntry("enabled", false).containsEntry("days", 365);
    }

    @Test
    void aRetriedChangeIsAuditedOnceWithThePreviousValue() {
        kit.store(TYPE, Map.of("enabled", true, "days", 14));

        service.patchReplayRetentionSettings(kit.server(), ACTOR, 1, null, 30);

        verify(auditor, times(1)).recordStaffAction(kit.server(), ACTOR, "Changed replay retention: 14 days to 30 days");
    }

    @Test
    void getReplayRetentionSettingsMergesPartialDataWithDefaults() {
        kit.store(TYPE, Map.of("days", 14));

        ReplayRetentionSettings settings = service.getReplayRetentionSettings(kit.server());

        assertThat(settings.isEnabled()).isTrue();
        assertThat(settings.getDays()).isEqualTo(14);
    }

    @Test
    void patchReplayRetentionSettingsRejectsEnabledZeroDays() {
        assertRejected(0, "Replay retention days must be at least 1 when enabled");
    }

    @Test
    void patchReplayRetentionSettingsRejectsEnabledNegativeDays() {
        assertRejected(-5, "Replay retention days must be at least 1 when enabled");
    }

    @Test
    void patchReplayRetentionSettingsRejectsEnabledDaysAboveMax() {
        assertRejected(366, "Replay retention days must be at most 365");
    }

    @Test
    void patchReplayRetentionSettingsNormalizesDisabledZeroDaysToMinimum() {
        assertThat(service.patchReplayRetentionSettings(kit.server(), ACTOR, 0, false, 0).data().getDays()).isEqualTo(1);
    }

    @Test
    void patchReplayRetentionSettingsNormalizesDisabledNegativeDaysToMinimum() {
        assertThat(service.patchReplayRetentionSettings(kit.server(), ACTOR, 0, false, -3).data().getDays()).isEqualTo(1);
    }

    @Test
    void getReplayRetentionSettingsNormalizesStoredZeroDaysToMinimum() {
        kit.store(TYPE, Map.of("enabled", true, "days", 0));

        assertThat(service.getReplayRetentionSettings(kit.server()).getDays()).isEqualTo(1);
    }

    @Test
    void getReplayRetentionSettingsNormalizesStoredDaysAboveMaxToMaximum() {
        kit.store(TYPE, Map.of("enabled", true, "days", 500));

        assertThat(service.getReplayRetentionSettings(kit.server()).getDays()).isEqualTo(365);
    }

    private void assertRejected(int days, String message) {
        assertThatThrownBy(() -> service.patchReplayRetentionSettings(kit.server(), ACTOR, 0, true, days))
            .isInstanceOf(ValidationException.class)
            .hasMessage(message);
        assertThat(kit.version(TYPE)).isZero();
        verifyNoInteractions(auditor);
    }
}
