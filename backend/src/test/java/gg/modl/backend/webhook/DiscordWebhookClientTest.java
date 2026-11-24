package gg.modl.backend.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.settings.data.WebhookSettings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DiscordWebhookClientTest {
    private final RestClient restClient = mock(RestClient.class);
    private final PublicUrls publicUrls = mock(PublicUrls.class);
    private final WebhookDeliveryStatus status = new WebhookDeliveryStatus();
    private final DiscordWebhookClient client = new DiscordWebhookClient(restClient, publicUrls, status,
        Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));

    DiscordWebhookClientTest() {
        when(publicUrls.origin()).thenReturn("https://panel.example.com");
    }

    @Test
    void anEmptyOrUnusableAvatarIsLeftOutAndARelativeOneIsMadeAbsolute() {
        assertThat(client.payload(settings(""), List.of(), false)).doesNotContainKey("avatar_url");
        assertThat(client.payload(settings("icon.png"), List.of(), false)).doesNotContainKey("avatar_url");
        assertThat(client.payload(settings("ftp://files.example/icon.png"), List.of(), false)).doesNotContainKey("avatar_url");
        assertThat(client.payload(settings("/v1/public/icons/panel.png"), List.of(), false))
            .containsEntry("avatar_url", "https://panel.example.com/v1/public/icons/panel.png");
        assertThat(client.payload(settings("https://cdn.example.com/a.png"), List.of(), false))
            .containsEntry("avatar_url", "https://cdn.example.com/a.png");
    }

    @Test
    void onlyTheConfiguredRoleCanBeMentioned() {
        WebhookSettings settings = settings("").toBuilder().discordAdminRoleId("42").build();

        Map<String, Object> mentioning = client.payload(settings, List.of(), true);
        Map<String, Object> quiet = client.payload(settings, List.of(), false);

        assertThat(mentioning).containsEntry("content", "<@&42>")
            .containsEntry("allowed_mentions", Map.of("parse", List.of(), "roles", List.of("42")));
        assertThat(quiet).doesNotContainKey("content")
            .containsEntry("allowed_mentions", Map.of("parse", List.of(), "roles", List.of()));
    }

    @Test
    void aBlankBotNameFallsBackToTheDefault() {
        assertThat(client.payload(settings("").toBuilder().botName(" ").build(), List.of(), false))
            .containsEntry("username", DiscordWebhookClient.DEFAULT_BOT_NAME);
    }

    @Test
    void aStoredUrlThatIsNoLongerAllowedIsRecordedAsAFailureWithoutAnyRequest() {
        boolean sent = client.send(settings("").toBuilder().discordWebhookUrl("https://evil.example/hook").build(),
            WebhookEvent.NEW_TICKETS, List.of(), false);

        assertThat(sent).isFalse();
        assertThat(status.lastFailure()).contains(new WebhookDeliveryFailure(Instant.ofEpochMilli(1_000L),
            WebhookEvent.NEW_TICKETS, WebhookFailureReason.INVALID_URL, 0));
        verifyNoInteractions(restClient);
    }

    private static WebhookSettings settings(String avatarUrl) {
        return WebhookSettings.builder()
            .discordWebhookUrl("https://discord.com/api/webhooks/1/token")
            .botName("Bot")
            .avatarUrl(avatarUrl)
            .discordAdminRoleId("")
            .build();
    }
}
