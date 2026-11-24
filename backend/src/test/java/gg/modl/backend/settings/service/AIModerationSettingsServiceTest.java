package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.settings.data.AIModerationSettings;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AIModerationSettingsServiceTest {
    private static final String SETTINGS_TYPE = "aiModerationSettings";

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private AIModerationSettingsService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        service = new AIModerationSettingsService(kit.documents(), mock(AiPunishmentEligibility.class));
        service.updateAIModerationSettings(kit.server(), 0, settings(true, true, Map.of("6", config("6"), "7", config("7"))));
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void updateDeletesConfigsAbsentFromRequest() {
        VersionedSettings<AIModerationSettings> saved =
            service.updateAIModerationSettings(kit.server(), 1, settings(true, true, Map.of("6", config("6"))));

        assertThat(saved.version()).isEqualTo(2);
        assertThat(saved.data().getAiPunishmentConfigs()).containsOnlyKeys("6");
        assertThat(service.getAIModerationSettings(kit.server())).isEqualTo(saved.data());
    }

    @Test
    void updateWithEmptyConfigsDeletesAllConfigs() {
        service.updateAIModerationSettings(kit.server(), 1, settings(true, false, Map.of()));

        AIModerationSettings stored = service.getAIModerationSettings(kit.server());
        assertThat(stored.getAiPunishmentConfigs()).isEmpty();
        assertThat(stored.isEnableAIReview()).isTrue();
        assertThat(stored.isEnableAutomatedActions()).isFalse();
    }

    @Test
    void updatePersistsTogglesAlongsideReplacedConfigs() {
        service.updateAIModerationSettings(kit.server(), 1, settings(false, true, Map.of("6", config("6"), "9", config("9"))));

        AIModerationSettings stored = service.getAIModerationSettings(kit.server());
        assertThat(stored.getAiPunishmentConfigs()).containsOnlyKeys("6", "9");
        assertThat(stored.isEnableAIReview()).isFalse();
        assertThat(stored.isEnableAutomatedActions()).isTrue();
        assertThat(kit.stored(SETTINGS_TYPE)).containsEntry("enableAIReview", false);
    }

    private static AIModerationSettings settings(boolean review, boolean automated,
                                                 Map<String, AIModerationSettings.AIPunishmentConfig> configs) {
        return AIModerationSettings.builder()
            .enableAIReview(review)
            .enableAutomatedActions(automated)
            .aiPunishmentConfigs(configs)
            .build();
    }

    private static AIModerationSettings.AIPunishmentConfig config(String id) {
        return AIModerationSettings.AIPunishmentConfig.builder()
            .id(id)
            .name("Config " + id)
            .aiDescription("Description " + id)
            .enabled(true)
            .build();
    }
}
