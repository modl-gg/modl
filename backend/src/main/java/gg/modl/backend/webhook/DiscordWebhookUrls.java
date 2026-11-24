package gg.modl.backend.webhook;

import org.jspecify.annotations.Nullable;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class DiscordWebhookUrls {
    private static final Set<String> DISCORD_HOSTS = Set.of(
        "discord.com", "canary.discord.com", "ptb.discord.com",
        "discordapp.com", "canary.discordapp.com", "ptb.discordapp.com");
    private static final Pattern WEBHOOK_PATH = Pattern.compile("^/api(/v\\d+)?/webhooks/\\d+/[A-Za-z0-9_-]+/?$");

    private DiscordWebhookUrls() {
    }

    public static boolean isAllowed(@Nullable String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(webhookUrl.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && DISCORD_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                && uri.getPort() == -1
                && uri.getRawUserInfo() == null
                && uri.getPath() != null
                && WEBHOOK_PATH.matcher(uri.getPath()).matches();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
