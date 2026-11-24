package gg.modl.backend.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.settings.data.SettingsType;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorLimitsTest {
    private static final String OWNER = "owner@example.com";

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private PanelActionAuditor auditor;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        auditor = mock(PanelActionAuditor.class);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void defaultsApplyWhenNothingIsSet() {
        OperatorLimits limits = limits(OperatorLimitsEnvironment.NONE);

        assertThat(limits.maxUploadBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(limits.limitsTotalStorage()).isFalse();
        assertThat(limits.keep()).isEqualTo(7);
        assertThat(limits.hour()).isEqualTo(4);
        assertThat(limits.ipHistoryDays()).isEqualTo(365);
        assertThat(limits.lifetime()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void panelValuesApplyAtOnceAndSurviveARestart() {
        OperatorLimits limits = limits(OperatorLimitsEnvironment.NONE);

        limits.change(kit.server(), OWNER, 0, OperatorLimitGroup.STORAGE, Map.of(OperatorLimit.MAX_STORAGE_GB, 20));
        limits.change(kit.server(), OWNER, 1, OperatorLimitGroup.RETENTION, Map.of(OperatorLimit.IP_HISTORY_DAYS, 30));

        assertThat(limits.maxStorageBytes()).isEqualTo(20L * 1024 * 1024 * 1024);
        assertThat(limits.cutoff(Instant.EPOCH.plus(Duration.ofDays(31)))).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1)));
        assertThat(limits(OperatorLimitsEnvironment.NONE).value(OperatorLimit.MAX_STORAGE_GB)).isEqualTo(20);
        verify(auditor).recordStaffAction(any(), eq(OWNER), eq("Changed operator limits: Total upload storage (GB, 0 = no limit) 0 to 20"));
        verify(auditor).recordStaffAction(any(), eq(OWNER), eq("Changed operator limits: IP history retention (days) 365 to 30"));
    }

    @Test
    void anEnvironmentVariableWinsAndLocksTheField() {
        OperatorLimits limits = limits(new OperatorLimitsEnvironment(null, null, 3, null, null, null));
        limits(OperatorLimitsEnvironment.NONE).change(kit.server(), OWNER, 0, OperatorLimitGroup.STORAGE, Map.of(OperatorLimit.BACKUP_KEEP, 9));

        assertThat(limits.keep()).isEqualTo(3);
        assertThat(limits.managed(OperatorLimit.BACKUP_KEEP)).isTrue();
        assertThatThrownBy(() -> limits.change(kit.server(), OWNER, 1, OperatorLimitGroup.STORAGE, Map.of(OperatorLimit.BACKUP_KEEP, 5)))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void resendingALockedValueUnchangedIsAccepted() {
        OperatorLimits limits = limits(new OperatorLimitsEnvironment(null, null, 3, null, null, null));

        limits.change(kit.server(), OWNER, 0, OperatorLimitGroup.STORAGE, Map.of(OperatorLimit.BACKUP_KEEP, 3, OperatorLimit.BACKUP_HOUR, 2));

        assertThat(limits.hour()).isEqualTo(2);
    }

    @Test
    void valuesOutsideTheirRangeAreRefusedAndNothingIsSaved() {
        OperatorLimits limits = limits(OperatorLimitsEnvironment.NONE);

        assertThatThrownBy(() -> limits.change(kit.server(), OWNER, 0, OperatorLimitGroup.STORAGE,
            Map.of(OperatorLimit.BACKUP_HOUR, 24, OperatorLimit.BACKUP_KEEP, 3))).isInstanceOf(ValidationException.class);
        assertThat(limits.keep()).isEqualTo(7);
    }

    @Test
    void retentionPeriodsCannotBeChangedThroughTheStorageLimits() {
        OperatorLimits limits = limits(OperatorLimitsEnvironment.NONE);

        assertThatThrownBy(() -> limits.change(kit.server(), OWNER, 0, OperatorLimitGroup.STORAGE,
            Map.of(OperatorLimit.IP_HISTORY_DAYS, 1, OperatorLimit.NOTIFICATION_DAYS, 1))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> limits.change(kit.server(), OWNER, 0, OperatorLimitGroup.RETENTION,
            Map.of(OperatorLimit.MAX_UPLOAD_MB, 1))).isInstanceOf(ValidationException.class);
        assertThat(limits.ipHistoryDays()).isEqualTo(365);
        assertThat(limits.all(OperatorLimitGroup.STORAGE)).extracting(EffectiveLimit::limit)
            .containsExactly(OperatorLimit.MAX_UPLOAD_MB, OperatorLimit.MAX_STORAGE_GB, OperatorLimit.BACKUP_KEEP, OperatorLimit.BACKUP_HOUR);
        assertThat(limits.all(OperatorLimitGroup.RETENTION)).extracting(EffectiveLimit::limit)
            .containsExactly(OperatorLimit.IP_HISTORY_DAYS, OperatorLimit.NOTIFICATION_DAYS);
    }

    @Test
    void anInvalidEnvironmentValueStopsTheStartWithTheVariableName() {
        assertThatThrownBy(() -> new OperatorLimitsEnvironment(null, null, 0, null, null, null))
            .hasMessageContaining("MODL_BACKUP_KEEP");
        assertThatThrownBy(() -> new OperatorLimitsEnvironment(null, null, null, null, 0, null))
            .hasMessageContaining("MODL_IP_HISTORY_DAYS");
    }

    @Test
    void aStoredValueOutsideTheRangeFallsBackToTheDefault() {
        kit.store(SettingsType.OPERATOR_LIMITS.id(), Map.of("values", Map.of("backupHour", 99)));

        assertThat(limits(OperatorLimitsEnvironment.NONE).hour()).isEqualTo(4);
    }

    private OperatorLimits limits(OperatorLimitsEnvironment environment) {
        return new OperatorLimits(environment, kit.documents(), kit.unitOfWork(), auditor);
    }
}
