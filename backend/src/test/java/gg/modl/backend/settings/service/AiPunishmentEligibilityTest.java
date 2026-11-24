package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.AIModerationSettings.AIPunishmentConfig;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.support.TestServers;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiPunishmentEligibilityTest {
    private final Server server = TestServers.server("server", "admin@example.com");
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final AiPunishmentEligibility eligibility = new AiPunishmentEligibility(types);

    @BeforeEach
    void defaultPunishmentTypes() {
        when(types.getPunishmentTypeByOrdinal(eq(server), anyInt())).thenAnswer(invocation -> DefaultPunishmentTypes.getAll().stream()
            .filter(type -> type.getOrdinal() == (int) invocation.getArgument(1))
            .findFirst());
    }

    @Test
    void savingAnEnabledManualOrIdentityRestrictedTypeIsRefusedWithAMachineCode() {
        for (String ordinal : new String[] {"2", "10", "999", "not-a-number"}) {
            assertThatThrownBy(() -> eligibility.requireEligible(server, Map.of(ordinal, config(ordinal, true))))
                .isInstanceOf(ValidationException.class)
                .extracting("machineCode")
                .isEqualTo(AiPunishmentEligibility.INELIGIBLE);
        }
    }

    @Test
    void savingADisabledIneligibleTypeOrAnEligibleTypeIsAccepted() {
        assertThatCode(() -> eligibility.requireEligible(server, Map.of("2", config("2", false), "6", config("6", true))))
            .doesNotThrowAnyException();
        assertThatCode(() -> eligibility.requireEligible(server, null)).doesNotThrowAnyException();
    }

    @Test
    void onlyEnabledEligibleTypesAreOfferedToTheModel() {
        Map<String, AIPunishmentConfig> configs = new LinkedHashMap<>();
        configs.put("2", config("2", true));
        configs.put("6", config("6", true));
        configs.put("7", config("7", false));
        AIModerationSettings settings = AIModerationSettings.builder().enableAIReview(true).aiPunishmentConfigs(configs).build();

        assertThat(eligibility.offered(server, settings)).extracting(AIPunishmentConfig::getId).containsExactly("6");
        assertThat(eligibility.accepts(server, settings, 6, "severe")).isTrue();
        assertThat(eligibility.accepts(server, settings, 2, "severe")).isFalse();
        assertThat(eligibility.accepts(server, settings, 7, "severe")).isFalse();
    }

    private static AIPunishmentConfig config(String id, boolean enabled) {
        return AIPunishmentConfig.builder().id(id).name("Type " + id).aiDescription("about " + id).enabled(enabled).build();
    }
}
