package gg.modl.backend.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DiscordWebhookUrlsTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "https://discord.com/api/webhooks/123/abc-DEF_9",
        "https://canary.discord.com/api/webhooks/123/token",
        "https://ptb.discord.com/api/webhooks/123/token",
        "https://discordapp.com/api/webhooks/123/token",
        "https://discord.com/api/v10/webhooks/123/token",
        "https://Discord.com/api/webhooks/123/token?thread_id=5"
    })
    void acceptsEveryDiscordWebhookHost(String url) {
        assertThat(DiscordWebhookUrls.isAllowed(url)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "http://discord.com/api/webhooks/123/token",
        "https://evil.example/api/webhooks/123/token",
        "https://discord.com.evil.example/api/webhooks/123/token",
        "https://discord.com:8443/api/webhooks/123/token",
        "https://user@discord.com/api/webhooks/123/token",
        "https://discord.com/api/channels/123",
        "https://discord.com/api/webhooks/abc/token",
        "not a url"
    })
    void refusesAnythingElse(String url) {
        assertThat(DiscordWebhookUrls.isAllowed(url)).isFalse();
    }
}
