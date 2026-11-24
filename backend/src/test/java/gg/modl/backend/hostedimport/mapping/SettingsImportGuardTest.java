package gg.modl.backend.hostedimport.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.ImportTestContexts;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.hostedimport.storage.ImportedUrls;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.SettingsType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SettingsImportGuardTest {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(SettingsType.class)
    void everyRegisteredSettingsTypeIsEitherImportedOrRefused(SettingsType type) {
        assertThat(SettingsImport.IMPORTED.contains(type.id())).isEqualTo(SettingsImport.isImported(type));
        assertThat(SettingsImport.REFUSED.contains(type.id())).isNotEqualTo(SettingsImport.isImported(type));
    }

    @Test
    void operatorOwnedAndInstanceStateSettingsAreNeverImported() {
        assertThat(SettingsImport.REFUSED).contains("operatorLimits", "storageBackend", "addressForwarding",
            "playerAddresses", "publicTheme", "publicCustomCss", "hostedImport", "hostedImportChoices", "hostedImportAliases");
        assertThat(SettingsImport.IMPORTED).doesNotContainAnyElementsOf(SettingsImport.REFUSED);
    }

    @Test
    void everySettingsTypeIdIsDistinct() {
        assertThat(Arrays.stream(SettingsType.values()).map(SettingsType::id).distinct().count())
            .isEqualTo(SettingsType.values().length);
    }

    @Test
    void aRelativeWebhookAvatarIsImportedWithoutTheOriginOfTheSetupSession() throws IOException {
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture()
            .line("settings", "{\"_id\":\"1\",\"type\":\"webhookSettings\",\"data\":{\"enabled\":false,"
                + "\"avatarUrl\":\"/avatar.png\"}}"), directory);
        IssueLog issues = new IssueLog();
        SettingsImport.SettingsImportPlan plan = new SettingsImport(index, VALIDATOR,
            new ImportedUrls(index.manifest(), index.keyPlan(), ImportTestContexts.storage(), issues), new ImportFit(issues))
            .plan(DefaultPunishmentTypes.getAll());

        assertThat(plan.documents().get("webhookSettings")).containsEntry("avatarUrl", "/avatar.png");
    }

    @Test
    void credentialAndSecretSettingsAreRefusedAndNeverWritten() throws IOException {
        assertThat(SettingsImport.REFUSED).contains("signIn", "mail", "aiApiKey", "aiProvider", "aiUsage", "apiKeys", "dataRetention");
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture()
            .line("settings", "{\"_id\":\"1\",\"type\":\"mail\",\"data\":{\"host\":\"evil.example.com\",\"password\":\"x\"}}")
            .line("settings", "{\"_id\":\"2\",\"type\":\"signIn\",\"data\":{\"method\":\"PASSWORD\"}}")
            .line("settings", "{\"_id\":\"3\",\"type\":\"webhookSettings\",\"data\":{\"enabled\":true,"
                + "\"discordWebhookUrl\":\"https://evil.example.com/hook\"}}")
            .line("settings", "{\"_id\":\"4\",\"type\":\"aiModerationSettings\",\"data\":{\"enableAIReview\":true,"
                + "\"enableAutomatedActions\":true,\"aiPunishmentConfigs\":{}}}"), directory);
        IssueLog issues = new IssueLog();
        SettingsImport.SettingsImportPlan plan = new SettingsImport(index, VALIDATOR,
            new ImportedUrls(index.manifest(), index.keyPlan(), ImportTestContexts.storage(), issues), new ImportFit(issues))
            .plan(DefaultPunishmentTypes.getAll());

        assertThat(plan.documents()).doesNotContainKeys("mail", "signIn");
        Map<String, Object> webhooks = plan.documents().get("webhookSettings");
        assertThat(webhooks).containsEntry("enabled", false).containsEntry("discordWebhookUrl", "");
        assertThat(plan.documents().get("aiModerationSettings")).containsEntry("enableAIReview", false)
            .containsEntry("enableAutomatedActions", false);
        assertThat(issues.tally().issues()).containsKeys(IssueCode.SETTING_IGNORED, IssueCode.WEBHOOK_DISABLED, IssueCode.AI_DISABLED);
    }
}
