package gg.modl.backend.settings.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.SettingsType;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AIModerationSettingsService {

    private final VersionedSettingsSupport<AIModerationSettings> support;
    private final AiPunishmentEligibility eligibility;

    public AIModerationSettingsService(SettingsDocumentService settingsDocumentService, AiPunishmentEligibility eligibility) {
        this.eligibility = eligibility;
        this.support = VersionedSettingsSupport.of(
            settingsDocumentService, SettingsType.AI_MODERATION, this::mapToAIModerationSettings);
    }

    public VersionedSettings<AIModerationSettings> updateAIModerationSettings(
        Server server,
        long expectedVersion,
        AIModerationSettings newSettings
    ) {
        eligibility.requireEligible(server, newSettings.getAiPunishmentConfigs());
        AIModerationSettings toPersist = AIModerationSettings.builder()
            .enableAIReview(newSettings.isEnableAIReview())
            .enableAutomatedActions(newSettings.isEnableAutomatedActions())
            .aiPunishmentConfigs(newSettings.getAiPunishmentConfigs() != null
                ? newSettings.getAiPunishmentConfigs()
                : new HashMap<>())
            .build();

        return support.save(server, expectedVersion, codec().encode(toPersist));
    }

    public AIModerationSettings getAIModerationSettings(Server server) {
        return support.get(server);
    }

    public VersionedSettings<AIModerationSettings> getAIModerationSettingsState(Server server) {
        return support.state(server);
    }

    private AIModerationSettings mapToAIModerationSettings(Map<String, Object> data) {
        return codec().decode(data);
    }

    private SettingsCodec<AIModerationSettings> codec() {
        return SettingsCodec.of(AIModerationSettings.class, this::createDefaultSettings);
    }

    private AIModerationSettings createDefaultSettings() {
        Map<String, AIModerationSettings.AIPunishmentConfig> defaultConfigs = new HashMap<>();
        defaultConfigs.put("6", AIModerationSettings.AIPunishmentConfig.builder()
            .id("6")
            .name("Chat Abuse")
            .aiDescription(
                "Chat abuse is the act of spamming, excessive profanity, abusive language, inappropriate topics or jokes, and misleading information")
            .enabled(true)
            .build());
        defaultConfigs.put("7", AIModerationSettings.AIPunishmentConfig.builder()
            .id("7")
            .name("Anti-Social")
            .aiDescription(
                "Anti-social behavior is the act of harassing, threatening, black-mailing, or otherwise abusing another player or group of players. This includes bigotry and other forms of discrimination against protected classes.")
            .enabled(true)
            .build());

        return AIModerationSettings.builder()
            .enableAIReview(false)
            .enableAutomatedActions(false)
            .aiPunishmentConfigs(defaultConfigs)
            .build();
    }
}
