package gg.modl.backend.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.settings.data.WebhookSettings;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DiscordWebhookDeliveryTest {
    private final DiscordWebhookClient client = mock(DiscordWebhookClient.class);
    private final PublicUrls publicUrls = mock(PublicUrls.class);
    private final DiscordWebhookDelivery delivery = new DiscordWebhookDelivery(client, publicUrls);

    DiscordWebhookDeliveryTest() {
        when(publicUrls.panel()).thenReturn("https://panel.example.com");
        when(publicUrls.staffTicket("T-1")).thenReturn("https://panel.example.com/panel/tickets/T-1");
        when(publicUrls.playerProfile("uuid-1")).thenReturn("https://panel.example.com/panel/player/uuid-1");
        when(publicUrls.staffAuditLog()).thenReturn("https://panel.example.com/panel/audit");
    }

    @Test
    void aTicketEmbedLinksToTheTicketInThePanelAndMentionsTheAdmins() {
        delivery.send(settings(true, true), message(new PendingWebhook(WebhookEvent.NEW_TICKETS, Map.of("id", "T-1"))));

        assertThat(fieldValues(sentEmbeds(WebhookEvent.NEW_TICKETS, true).get(0)))
            .contains("[Open](https://panel.example.com/panel/tickets/T-1)");
    }

    @Test
    void aPunishmentEmbedLinksToThePlayer() {
        delivery.send(settings(true, true),
            message(new PendingWebhook(WebhookEvent.NEW_PUNISHMENTS, Map.of("id", "P-1", "playerUuid", "uuid-1"))));

        assertThat(fieldValues(sentEmbeds(WebhookEvent.NEW_PUNISHMENTS, true).get(0)))
            .contains("[Open](https://panel.example.com/panel/player/uuid-1)");
    }

    @Test
    void onlyTicketsAndPunishmentsMentionTheAdmins() {
        delivery.send(settings(true, true), message(audit(1), audit(2)));

        assertThat(sentEmbeds(WebhookEvent.AUDIT_LOGS, false)).hasSize(2);
    }

    @Test
    void theWebhookOrAnEventThatIsOffIsNeverDelivered() {
        assertThat(delivery.delivers(settings(false, true), WebhookEvent.NEW_TICKETS)).isFalse();
        assertThat(delivery.delivers(settings(true, false), WebhookEvent.AUDIT_LOGS)).isFalse();
        assertThat(delivery.delivers(settings(true, true), WebhookEvent.AUDIT_LOGS)).isTrue();
    }

    @Test
    void eventsThatDidNotFitAreSummarizedPerEventWithinDiscordsLimits() {
        List<PendingWebhook> items = IntStream.range(0, DiscordWebhookDelivery.MAX_ITEMS_PER_MESSAGE)
            .mapToObj(DiscordWebhookDeliveryTest::audit).toList();

        delivery.send(settings(true, true), new WebhookMessage(items,
            Map.of(WebhookEvent.AUDIT_LOGS, 5, WebhookEvent.NEW_PUNISHMENTS, 1)));

        List<DiscordEmbed> embeds = sentEmbeds(WebhookEvent.AUDIT_LOGS, false);
        assertThat(embeds).hasSize(DiscordWebhookDelivery.MAX_EMBEDS_PER_MESSAGE);
        assertThat(embeds.getLast().content().description())
            .isEqualTo("Not sent, to stay within Discord's limits: 1 punishment, 5 audit log entries. See https://panel.example.com");
        assertThat(embeds.stream().mapToInt(DiscordEmbed::length).sum()).isLessThanOrEqualTo(DiscordEmbedLimits.TOTAL);
    }

    @Test
    void aSmallBatchHasNoSummary() {
        delivery.send(settings(true, true), message(audit(1), audit(2)));

        assertThat(sentEmbeds(WebhookEvent.AUDIT_LOGS, false)).noneMatch(embed -> embed.content().title().equals("More events"));
    }

    @SuppressWarnings("unchecked")
    private List<DiscordEmbed> sentEmbeds(WebhookEvent event, boolean mentionAdmins) {
        ArgumentCaptor<List<DiscordEmbed>> embeds = ArgumentCaptor.forClass(List.class);
        verify(client).send(any(), eq(event), embeds.capture(), eq(mentionAdmins));
        return embeds.getValue();
    }

    private static WebhookMessage message(PendingWebhook... items) {
        return new WebhookMessage(List.of(items), Map.of());
    }

    private static List<String> fieldValues(DiscordEmbed embed) {
        return embed.content().fields().stream().map(DiscordEmbedLimits.Field::value).toList();
    }

    private static PendingWebhook audit(int index) {
        return new PendingWebhook(WebhookEvent.AUDIT_LOGS,
            Map.of("user", "Mod" + index, "action", "Ban issued for Player" + index, "target", "Player" + index));
    }

    private static WebhookSettings settings(boolean enabled, boolean eventsOn) {
        return WebhookSettings.builder()
            .discordWebhookUrl("https://discord.com/api/webhooks/1/token")
            .enabled(enabled)
            .notifications(new WebhookSettings.NotificationSettings(eventsOn, eventsOn, eventsOn))
            .embedTemplates(new WebhookSettings.EmbedTemplates(
                template("{{ticketUrl}}"), template("{{playerUrl}}"), template("{{auditUrl}}")))
            .build();
    }

    private static WebhookSettings.EmbedTemplate template(String link) {
        return WebhookSettings.EmbedTemplate.builder()
            .title("Event").description("{{action}}").color("#ffffff")
            .fields(List.of(
                new WebhookSettings.EmbedField("User", "{{user}}", true),
                new WebhookSettings.EmbedField("Panel", "[Open](" + link + ")", false)))
            .build();
    }
}
