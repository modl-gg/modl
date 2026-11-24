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
import gg.modl.backend.settings.data.DataRetentionSettings;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataRetentionSettingsServiceTest {
    private static final String TYPE = "dataRetention";
    private static final String ACTOR = "owner@example.com";

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private PanelActionAuditor auditor;
    private DataRetentionSettingsService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        auditor = mock(PanelActionAuditor.class);
        service = new DataRetentionSettingsService(kit.documents(), auditor);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void defaultsKeepChatLogsForThirtyDaysAndTheAuditLogForAYear() {
        VersionedSettings<DataRetentionSettings> settings = service.getDataRetentionSettingsState(kit.server());

        assertThat(settings.version()).isZero();
        assertThat(settings.data()).isEqualTo(new DataRetentionSettings(30, 365));
    }

    @Test
    void storedValuesOutsideTheAllowedRangeAreClampedOnRead() {
        kit.store(TYPE, Map.of("chatLogDays", 0, "auditLogDays", 99_999));

        assertThat(service.getDataRetentionSettings(kit.server())).isEqualTo(new DataRetentionSettings(1, 3650));
    }

    @Test
    void aPartialPatchKeepsTheOtherPeriod() {
        kit.store(TYPE, Map.of("chatLogDays", 14, "auditLogDays", 400));

        VersionedSettings<DataRetentionSettings> updated = service.patchDataRetentionSettings(kit.server(), ACTOR, 1, 7, null);

        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.data()).isEqualTo(new DataRetentionSettings(7, 400));
        verify(auditor, times(1)).recordStaffAction(kit.server(), ACTOR,
            "Changed data retention: chat and command logs 14 to 7 days, audit log 400 to 400 days");
    }

    @Test
    void periodsOutsideTheAllowedRangeAreRejected() {
        assertThatThrownBy(() -> service.patchDataRetentionSettings(kit.server(), ACTOR, 0, 0, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.patchDataRetentionSettings(kit.server(), ACTOR, 0, 3651, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.patchDataRetentionSettings(kit.server(), ACTOR, 0, null, 29)).isInstanceOf(ValidationException.class);
        assertThat(kit.version(TYPE)).isZero();
        verifyNoInteractions(auditor);
    }
}
