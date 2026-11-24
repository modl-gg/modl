package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.OffenderThresholdSettings.CategoryThresholds;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OffenderThresholdSettingsServiceTest {
    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private OffenderThresholdSettingsService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        service = new OffenderThresholdSettingsService(kit.documents(), kit.unitOfWork(), kit.clock());
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void aCommittedPatchReplacesTheCachedValueAndKeepsTheOtherCategory() {
        OffenderThresholdSettings before = service.getThresholdSettings(kit.server());

        service.patchThresholdSettings(kit.server(), 0, socialOnly(7, 14));

        OffenderThresholdSettings after = service.getThresholdSettings(kit.server());
        assertThat(after.getSocial().getMedium()).isEqualTo(7);
        assertThat(after.getGameplay()).isEqualTo(before.getGameplay());
    }

    @Test
    void habitualNeverFallsBelowMedium() {
        VersionedSettings<OffenderThresholdSettings> saved = service.patchThresholdSettings(kit.server(), 0, socialOnly(9, 3));

        assertThat(saved.data().getSocial().getHabitual()).isEqualTo(9);
    }

    @Test
    void aStalePatchLeavesTheStoredThresholds() {
        service.patchThresholdSettings(kit.server(), 0, socialOnly(7, 14));

        assertThatThrownBy(() -> service.patchThresholdSettings(kit.server(), 0, socialOnly(1, 2)))
            .isInstanceOf(StaleClientVersionException.class);
        assertThat(service.getThresholdSettings(kit.server()).getSocial().getMedium()).isEqualTo(7);
    }

    private static OffenderThresholdSettings socialOnly(int medium, int habitual) {
        return OffenderThresholdSettings.builder().social(new CategoryThresholds(medium, habitual, 12)).build();
    }
}
