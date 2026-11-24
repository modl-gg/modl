package gg.modl.backend.settings.controller;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.data.WebhookSettings;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.backend.webhook.WebhookDeliveryFailure;
import gg.modl.backend.webhook.WebhookEvent;
import gg.modl.backend.webhook.WebhookFailureReason;
import gg.modl.proto.modl.v1.AppealForm;
import gg.modl.proto.modl.v1.AppealFormField;
import gg.modl.proto.modl.v1.AppealFormSection;
import gg.modl.proto.modl.v1.PunishmentTypeRequest;
import gg.modl.proto.modl.v1.UpdateWebhookSettingsRequest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PanelSettingsProtoMapperTest {

    @Test
    void appealFormOptionsMappingsAndHiddenSectionsSurviveBothDirections() {
        AppealForm form = AppealForm.newBuilder()
            .addFields(AppealFormField.newBuilder()
                .setId("kind")
                .setType("dropdown")
                .addOptions("Mistake")
                .addOptions("Other")
                .putOptionSectionMapping("Other", "details"))
            .addSections(AppealFormSection.newBuilder().setId("details").setHideByDefault(true))
            .build();

        PunishmentType stored = PanelSettingsProtoMapper.fromPunishmentTypeRequest(
            PunishmentTypeRequest.newBuilder().setName("Griefing").setAppealForm(form).build());

        assertThat(stored.getAppealForm().getFields().getFirst().getOptions()).containsExactly("Mistake", "Other");
        assertThat(stored.getAppealForm().getFields().getFirst().getOptionSectionMapping()).containsEntry("Other", "details");
        assertThat(stored.getAppealForm().getSections().getFirst().isHideByDefault()).isTrue();
        assertThat(PanelSettingsProtoMapper.toPunishmentType(stored).getAppealForm()).isEqualTo(form);
    }

    @Test
    void thePunishmentTypeResponseTellsThePanelWhichTypesTakeAiSuggestions() {
        PunishmentType customKick = PunishmentType.builder().name("Warn Kick").category("Kick").ordinal(30).build();
        PunishmentType manualBan = PunishmentType.builder().name("Manual Ban").category("Ban").ordinal(2).build();
        PunishmentType identityKick = PunishmentType.builder().name("Skin Kick").category("Kick").ordinal(31)
            .permanentUntilSkinChange(true).build();

        assertThat(PanelSettingsProtoMapper.toPunishmentType(customKick).getAcceptsAiSuggestions()).isTrue();
        assertThat(PanelSettingsProtoMapper.toPunishmentType(manualBan).getAcceptsAiSuggestions()).isFalse();
        assertThat(PanelSettingsProtoMapper.toPunishmentType(identityKick).getAcceptsAiSuggestions()).isFalse();
        assertThat(PanelSettingsProtoMapper.toPunishmentType(manualBan).hasAcceptsAiSuggestions()).isTrue();
    }

    @Test
    void webhookAndAiModerationResponsesCarryTheirVersion() {
        assertThat(PanelSettingsProtoMapper.toWebhookSettings(
            new VersionedSettings<>(WebhookSettings.builder().build(), 7, null), Optional.empty()).getVersion()).isEqualTo(7);
        assertThat(PanelSettingsProtoMapper.toAIModerationSettings(
            new VersionedSettings<>(AIModerationSettings.builder().build(), 9, null)).getVersion()).isEqualTo(9);
    }

    @Test
    void theWebhookResponseNeverCarriesTheSecretUrl() {
        gg.modl.proto.modl.v1.WebhookSettings response = PanelSettingsProtoMapper.toWebhookSettings(new VersionedSettings<>(
            WebhookSettings.builder().discordWebhookUrl("https://discord.com/api/webhooks/1/secret-token-abcd").build(), 1, null),
            Optional.empty());

        assertThat(response.getDiscordWebhookConfigured()).isTrue();
        assertThat(response.getDiscordWebhookUrlSuffix()).isEqualTo("abcd");
        assertThat(response.toString()).doesNotContain("secret-token");
        assertThat(PanelSettingsProtoMapper.toWebhookSettings(new VersionedSettings<>(WebhookSettings.builder().build(), 1, null),
            Optional.empty()).getDiscordWebhookConfigured()).isFalse();
    }

    @Test
    void theLastDeliveryFailureIsShownWithoutTheWebhookUrl() {
        gg.modl.proto.modl.v1.WebhookSettings response = PanelSettingsProtoMapper.toWebhookSettings(new VersionedSettings<>(
            WebhookSettings.builder().discordWebhookUrl("https://discord.com/api/webhooks/1/secret-token-abcd").build(), 1, null),
            Optional.of(new WebhookDeliveryFailure(Instant.ofEpochMilli(1_700_000_000_000L), WebhookEvent.AUDIT_LOGS,
                WebhookFailureReason.REJECTED, 400)));

        assertThat(response.getLastDeliveryFailure().getFailedAt()).isEqualTo(1_700_000_000_000L);
        assertThat(response.getLastDeliveryFailure().getEvent()).isEqualTo("auditLogs");
        assertThat(response.getLastDeliveryFailure().getReason()).isEqualTo("REJECTED");
        assertThat(response.getLastDeliveryFailure().getHttpStatus()).isEqualTo(400);
        assertThat(response.toString()).doesNotContain("secret-token");
    }

    @Test
    void anUpdateWithoutAWebhookUrlKeepsTheStoredOne() {
        assertThat(PanelSettingsProtoMapper.fromUpdateWebhookSettingsRequest(
            UpdateWebhookSettingsRequest.newBuilder().setBotName("bot").build()).replacementWebhookUrl()).isNull();
        assertThat(PanelSettingsProtoMapper.fromUpdateWebhookSettingsRequest(
            UpdateWebhookSettingsRequest.newBuilder().setDiscordWebhookUrl("").build()).replacementWebhookUrl()).isEmpty();
    }
}
