package gg.modl.backend.settings.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.AIModerationSettings.AIPunishmentConfig;
import gg.modl.backend.settings.data.PunishmentType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiPunishmentEligibility {
    public static final String INELIGIBLE = "AI_PUNISHMENT_TYPE_INELIGIBLE";

    private final PunishmentTypeService punishmentTypeService;

    public void requireEligible(Server server, @Nullable Map<String, AIPunishmentConfig> configs) {
        List<String> refused = enabled(configs).stream()
            .filter(config -> !eligible(server, config))
            .map(config -> config.getName() != null ? config.getName() : config.getId())
            .toList();
        if (!refused.isEmpty()) {
            throw new ValidationException("These punishment types cannot take AI suggestions because they are manual, "
                + "identity-restricted or lack a length for every severity and offense level: " + String.join(", ", refused),
                INELIGIBLE);
        }
    }

    public List<AIPunishmentConfig> offered(Server server, AIModerationSettings settings) {
        return enabled(settings.getAiPunishmentConfigs()).stream().filter(config -> eligible(server, config)).toList();
    }

    public boolean accepts(Server server, AIModerationSettings settings, int typeOrdinal, String severity) {
        Map<String, AIPunishmentConfig> configs = settings.getAiPunishmentConfigs();
        AIPunishmentConfig config = configs == null ? null : configs.get(String.valueOf(typeOrdinal));
        return config != null && config.isEnabled() && punishmentTypeService.getPunishmentTypeByOrdinal(server, typeOrdinal)
            .filter(type -> type.acceptsAiSuggestion(severity))
            .isPresent();
    }

    private boolean eligible(Server server, AIPunishmentConfig config) {
        return ordinal(config.getId())
            .flatMap(ordinal -> punishmentTypeService.getPunishmentTypeByOrdinal(server, ordinal))
            .filter(PunishmentType::acceptsAiSuggestions)
            .isPresent();
    }

    private static List<AIPunishmentConfig> enabled(@Nullable Map<String, AIPunishmentConfig> configs) {
        return configs == null ? List.of() : configs.values().stream().filter(AIPunishmentConfig::isEnabled).toList();
    }

    private static Optional<Integer> ordinal(@Nullable String id) {
        if (id == null || !id.matches("\\d{1,9}")) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(id));
    }
}
