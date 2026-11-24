package gg.modl.backend.settings.data;

import org.jspecify.annotations.Nullable;
import java.util.Objects;

public record WebhookSettingsChange(WebhookSettings settings, @Nullable String replacementWebhookUrl) {
    public WebhookSettingsChange {
        Objects.requireNonNull(settings, "settings");
    }

    public WebhookSettings appliedOver(@Nullable String storedWebhookUrl) {
        String webhookUrl = replacementWebhookUrl != null ? replacementWebhookUrl : storedWebhookUrl;
        return settings.toBuilder().discordWebhookUrl(webhookUrl).build();
    }
}
