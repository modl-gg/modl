package gg.modl.backend.settings.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.data.WebhookSettings;
import gg.modl.backend.settings.data.WebhookSettingsChange;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.webhook.DiscordWebhookDelivery;
import gg.modl.backend.webhook.DiscordWebhookUrls;
import gg.modl.backend.webhook.WebhookDeliveryFailure;
import gg.modl.backend.webhook.WebhookDeliveryStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class WebhookSettingsService {
    private static final String DEFAULT_BOT_NAME = "modl Panel";
    private static final String INVALID_WEBHOOK_URL_CODE = "WEBHOOK_URL_INVALID";
    private static final String INVALID_WEBHOOK_URL_MESSAGE =
        "The webhook URL must be a Discord webhook URL such as https://discord.com/api/webhooks/<id>/<token>";

    private final DiscordWebhookDelivery delivery;
    private final WebhookDeliveryStatus deliveryStatus;
    private final VersionedSettingsSupport<WebhookSettings> support;

    public WebhookSettingsService(
        SettingsDocumentService settingsDocumentService,
        DiscordWebhookDelivery delivery,
        WebhookDeliveryStatus deliveryStatus
    ) {
        this.delivery = delivery;
        this.deliveryStatus = deliveryStatus;
        this.support = VersionedSettingsSupport.of(
            settingsDocumentService, SettingsType.WEBHOOKS, this::mapToWebhookSettings);
    }

    public VersionedSettings<WebhookSettings> updateWebhookSettings(Server server, long expectedVersion, WebhookSettingsChange change) {
        requireAllowedReplacement(change);
        return support.update(server, expectedVersion,
            current -> codec().encode(change.appliedOver(codec().decode(current).getDiscordWebhookUrl())));
    }

    public WebhookSettings getWebhookSettings(Server server) {
        return support.get(server);
    }

    public VersionedSettings<WebhookSettings> getWebhookSettingsState(Server server) {
        return support.state(server);
    }

    private WebhookSettings mapToWebhookSettings(Map<String, Object> data) {
        return withDefaultTemplates(codec().decode(data));
    }

    private WebhookSettings withDefaultTemplates(WebhookSettings settings) {
        WebhookSettings.EmbedTemplates stored = settings.getEmbedTemplates();
        if (stored == null) {
            return settings.toBuilder().embedTemplates(defaultTemplates()).build();
        }
        return settings.toBuilder().embedTemplates(WebhookSettings.EmbedTemplates.builder()
                .newTickets(stored.getNewTickets() != null ? stored.getNewTickets() : createDefaultTicketEmbed())
                .newPunishments(stored.getNewPunishments() != null ? stored.getNewPunishments() : createDefaultPunishmentEmbed())
                .auditLogs(stored.getAuditLogs() != null ? stored.getAuditLogs() : createDefaultAuditEmbed())
                .build())
            .build();
    }

    private WebhookSettings.EmbedTemplates defaultTemplates() {
        return WebhookSettings.EmbedTemplates.builder()
            .newTickets(createDefaultTicketEmbed())
            .newPunishments(createDefaultPunishmentEmbed())
            .auditLogs(createDefaultAuditEmbed())
            .build();
    }

    private SettingsCodec<WebhookSettings> codec() {
        return SettingsCodec.of(WebhookSettings.class, this::getDefaultWebhookSettings);
    }

    private WebhookSettings getDefaultWebhookSettings() {
        return WebhookSettings.builder()
            .discordWebhookUrl("")
            .discordAdminRoleId("")
            .botName(DEFAULT_BOT_NAME)
            .avatarUrl("")
            .enabled(false)
            .notifications(WebhookSettings.NotificationSettings.builder()
                .newTickets(true)
                .newPunishments(true)
                .auditLogs(false)
                .build())
            .embedTemplates(defaultTemplates())
            .build();
    }

    private WebhookSettings.EmbedTemplate createDefaultTicketEmbed() {
        return WebhookSettings.EmbedTemplate.builder()
            .title("New Ticket Created")
            .description("A new **{{type}}** ticket has been submitted.")
            .color("#3498db")
            .fields(List.of(
                new WebhookSettings.EmbedField("Ticket ID", "#{{id}}", true),
                new WebhookSettings.EmbedField("Priority", "{{priority}}", true),
                new WebhookSettings.EmbedField("Category", "{{category}}", true),
                new WebhookSettings.EmbedField("Subject", "{{title}}", false),
                new WebhookSettings.EmbedField("Submitted By", "{{submittedBy}}", true),
                new WebhookSettings.EmbedField("Panel", "[Open the ticket]({{ticketUrl}})", false)
            ))
            .build();
    }

    private WebhookSettings.EmbedTemplate createDefaultPunishmentEmbed() {
        return WebhookSettings.EmbedTemplate.builder()
            .title("New Punishment Issued")
            .description("A new **{{type}}** punishment has been issued.")
            .color("#e74c3c")
            .fields(List.of(
                new WebhookSettings.EmbedField("Player", "{{playerName}}", true),
                new WebhookSettings.EmbedField("Punishment Type", "{{type}}", true),
                new WebhookSettings.EmbedField("Severity", "{{severity}}", true),
                new WebhookSettings.EmbedField("Duration", "{{duration}}", true),
                new WebhookSettings.EmbedField("Reason", "{{reason}}", false),
                new WebhookSettings.EmbedField("Issued By", "{{issuer}}", true),
                new WebhookSettings.EmbedField("Panel", "[Open the player]({{playerUrl}})", false)
            ))
            .build();
    }

    private WebhookSettings.EmbedTemplate createDefaultAuditEmbed() {
        return WebhookSettings.EmbedTemplate.builder()
            .title("Audit Log Entry")
            .description("A new audit log entry has been recorded.")
            .color("#f39c12")
            .fields(List.of(
                new WebhookSettings.EmbedField("User", "{{user}}", true),
                new WebhookSettings.EmbedField("Action", "{{action}}", true),
                new WebhookSettings.EmbedField("Target", "{{target}}", true),
                new WebhookSettings.EmbedField("Details", "{{details}}", false),
                new WebhookSettings.EmbedField("Panel", "[Open the audit log]({{auditUrl}})", false)
            ))
            .build();
    }

    public boolean testWebhook(Server server) {
        WebhookSettings settings = getWebhookSettings(server);
        if (settings.getDiscordWebhookUrl() == null || settings.getDiscordWebhookUrl().isBlank()) {
            return false;
        }
        return delivery.test(settings);
    }

    public Optional<WebhookDeliveryFailure> lastDeliveryFailure() {
        return deliveryStatus.lastFailure();
    }

    private static void requireAllowedReplacement(WebhookSettingsChange change) {
        String replacement = change.replacementWebhookUrl();
        if (replacement != null && !replacement.isBlank() && !DiscordWebhookUrls.isAllowed(replacement)) {
            throw new ValidationException(INVALID_WEBHOOK_URL_MESSAGE, INVALID_WEBHOOK_URL_CODE);
        }
    }
}
