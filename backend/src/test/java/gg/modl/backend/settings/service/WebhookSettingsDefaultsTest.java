package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.data.WebhookSettings;
import gg.modl.backend.settings.service.SettingsDocumentService.RawSettingsState;
import gg.modl.backend.webhook.DiscordWebhookDelivery;
import gg.modl.backend.webhook.WebhookDeliveryStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookSettingsDefaultsTest {
    private final SettingsDocumentService documents = mock(SettingsDocumentService.class);
    private final WebhookSettingsService service =
        new WebhookSettingsService(documents, mock(DiscordWebhookDelivery.class), new WebhookDeliveryStatus());

    @Test
    void aStoredDocumentWithoutTemplatesReadsTheDefaultTemplates() {
        stored(Map.of("enabled", true));

        WebhookSettings.EmbedTemplates templates = service.getWebhookSettings(mock(Server.class)).getEmbedTemplates();

        assertThat(templates).isNotNull();
        assertThat(templates.getNewTickets()).extracting(WebhookSettings.EmbedTemplate::getTitle).isEqualTo("New Ticket Created");
        assertThat(templates.getNewPunishments()).extracting(WebhookSettings.EmbedTemplate::getTitle).isEqualTo("New Punishment Issued");
        assertThat(templates.getAuditLogs()).extracting(WebhookSettings.EmbedTemplate::getTitle).isEqualTo("Audit Log Entry");
    }

    @Test
    void aStoredTemplateIsKeptAndOnlyMissingOnesAreFilledIn() {
        Map<String, Object> custom = Map.of("title", "Custom", "description", "d", "color", "#000000", "fields", List.of());
        stored(Map.of("enabled", true, "embedTemplates", Map.of("newTickets", custom)));

        WebhookSettings.EmbedTemplates templates = service.getWebhookSettings(mock(Server.class)).getEmbedTemplates();

        assertThat(templates).isNotNull();
        assertThat(templates.getNewTickets()).extracting(WebhookSettings.EmbedTemplate::getTitle).isEqualTo("Custom");
        assertThat(templates.getAuditLogs()).extracting(WebhookSettings.EmbedTemplate::getTitle).isEqualTo("Audit Log Entry");
    }

    private void stored(Map<String, Object> data) {
        when(documents.getRawState(SettingsType.WEBHOOKS.id())).thenReturn(new RawSettingsState(data, 3, null, true));
    }
}
