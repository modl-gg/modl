package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.WebhookSettings;
import gg.modl.backend.settings.data.WebhookSettingsChange;
import gg.modl.backend.webhook.DiscordWebhookDelivery;
import gg.modl.backend.webhook.WebhookDeliveryStatus;
import org.junit.jupiter.api.Test;

class WebhookSettingsValidationTest {
    private final SettingsDocumentService documents = mock(SettingsDocumentService.class);
    private final WebhookSettingsService service =
        new WebhookSettingsService(documents, mock(DiscordWebhookDelivery.class), new WebhookDeliveryStatus());

    @Test
    void aWebhookUrlOutsideDiscordIsRefusedBeforeAnythingIsSaved() {
        WebhookSettingsChange change = new WebhookSettingsChange(WebhookSettings.builder().build(), "https://evil.example/hook");

        assertThatThrownBy(() -> service.updateWebhookSettings(mock(Server.class), 1, change))
            .isInstanceOf(ValidationException.class);
        verifyNoInteractions(documents);
    }
}
