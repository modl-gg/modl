package gg.modl.backend.webhook;

import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.settings.data.WebhookSettings;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DiscordWebhookDelivery {
    static final int MAX_EMBEDS_PER_MESSAGE = 10;
    static final int MAX_ITEMS_PER_MESSAGE = MAX_EMBEDS_PER_MESSAGE - 1;
    private static final int MESSAGE_BUDGET = DiscordEmbedLimits.TOTAL;
    private static final int SUMMARY_RESERVE = 300;
    private static final int ITEM_BUDGET = (MESSAGE_BUDGET - SUMMARY_RESERVE) / MAX_ITEMS_PER_MESSAGE;
    private static final int TEST_COLOR = DiscordEmbed.DEFAULT_COLOR;
    private static final int SUMMARY_COLOR = 0xF39C12;
    private static final String PANEL_URL = "panelUrl";
    private static final String TICKET_URL = "ticketUrl";
    private static final String PLAYER_URL = "playerUrl";
    private static final String AUDIT_URL = "auditUrl";

    private final DiscordWebhookClient client;
    private final PublicUrls publicUrls;

    public boolean delivers(WebhookSettings settings, WebhookEvent event) {
        if (!settings.isEnabled() || settings.getDiscordWebhookUrl() == null || settings.getDiscordWebhookUrl().isBlank()) {
            return false;
        }
        WebhookSettings.NotificationSettings notifications = settings.getNotifications();
        return switch (event) {
            case NEW_TICKETS -> notifications != null && notifications.isNewTickets();
            case NEW_PUNISHMENTS -> notifications != null && notifications.isNewPunishments();
            case AUDIT_LOGS -> notifications != null && notifications.isAuditLogs();
            case TEST -> true;
        };
    }

    public boolean test(WebhookSettings settings) {
        return client.send(settings, WebhookEvent.TEST, List.of(DiscordEmbed.plain("Webhook Test",
            "This is a test message from modl to check that your webhook works.", TEST_COLOR)), false);
    }

    void send(WebhookSettings settings, WebhookMessage message) {
        List<DiscordEmbed> embeds = new ArrayList<>();
        Map<WebhookEvent, Integer> notSent = new EnumMap<>(WebhookEvent.class);
        notSent.putAll(message.notSent());
        WebhookEvent leading = null;
        boolean mentionAdmins = false;
        int used = 0;
        for (PendingWebhook item : message.items()) {
            WebhookSettings.EmbedTemplate template = templateFor(settings, item.event());
            if (template == null) {
                continue;
            }
            DiscordEmbed embed = DiscordEmbed.fromTemplate(template, withLinks(item.event(), item.variables()), ITEM_BUDGET);
            if (embeds.size() == MAX_ITEMS_PER_MESSAGE || used + embed.length() > MESSAGE_BUDGET - SUMMARY_RESERVE) {
                notSent.merge(item.event(), 1, Integer::sum);
                continue;
            }
            embeds.add(embed);
            used += embed.length();
            leading = leading == null ? item.event() : leading;
            mentionAdmins |= item.event().mentionsAdmins();
        }
        if (!notSent.isEmpty()) {
            embeds.add(summaryOf(notSent));
            leading = leading == null ? notSent.keySet().iterator().next() : leading;
        }
        if (leading != null) {
            client.send(settings, leading, embeds, mentionAdmins);
        }
    }

    private DiscordEmbed summaryOf(Map<WebhookEvent, Integer> notSent) {
        String counts = notSent.entrySet().stream()
            .map(entry -> entry.getKey().countedAs(entry.getValue()))
            .collect(Collectors.joining(", "));
        return DiscordEmbed.plain("More events", "Not sent, to stay within Discord's limits: " + counts + ". See "
            + publicUrls.panel(), SUMMARY_COLOR);
    }

    private static WebhookSettings.@Nullable EmbedTemplate templateFor(WebhookSettings settings, WebhookEvent event) {
        WebhookSettings.EmbedTemplates templates = settings.getEmbedTemplates();
        if (templates == null) {
            return null;
        }
        return switch (event) {
            case NEW_TICKETS -> templates.getNewTickets();
            case NEW_PUNISHMENTS -> templates.getNewPunishments();
            case AUDIT_LOGS -> templates.getAuditLogs();
            case TEST -> null;
        };
    }

    private Map<String, String> withLinks(WebhookEvent event, Map<String, String> variables) {
        Map<String, String> linked = new HashMap<>(variables);
        linked.put(PANEL_URL, publicUrls.panel());
        switch (event) {
            case NEW_TICKETS -> linked.put(TICKET_URL, linkOr(variables.get("id"), publicUrls::staffTicket));
            case NEW_PUNISHMENTS -> linked.put(PLAYER_URL, linkOr(variables.get("playerUuid"), publicUrls::playerProfile));
            case AUDIT_LOGS -> linked.put(AUDIT_URL, publicUrls.staffAuditLog());
            case TEST -> { }
        }
        return linked;
    }

    private String linkOr(@Nullable String id, Function<String, String> link) {
        return id == null || id.isBlank() ? publicUrls.panel() : link.apply(id);
    }
}
