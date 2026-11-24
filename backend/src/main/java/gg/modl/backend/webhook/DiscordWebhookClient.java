package gg.modl.backend.webhook;

import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.settings.data.WebhookSettings;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
@Slf4j
public class DiscordWebhookClient {
    static final String DEFAULT_BOT_NAME = "modl Panel";
    private static final Pattern ROLE_ID = Pattern.compile("^[0-9]{1,20}$");
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int FIRST_SERVER_ERROR = 500;
    private static final int NO_HTTP_STATUS = 0;

    private final RestClient restClient;
    private final PublicUrls publicUrls;
    private final WebhookDeliveryStatus deliveryStatus;
    private final Clock clock;

    public boolean send(WebhookSettings settings, WebhookEvent event, List<DiscordEmbed> embeds, boolean mentionAdmins) {
        String webhookUrl = settings.getDiscordWebhookUrl();
        if (webhookUrl == null || !DiscordWebhookUrls.isAllowed(webhookUrl)) {
            failed(event, WebhookFailureReason.INVALID_URL, NO_HTTP_STATUS);
            return false;
        }
        try {
            restClient.post()
                .uri(URI.create(webhookUrl.trim()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload(settings, embeds, mentionAdmins))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (RestClientResponseException rejected) {
            int status = rejected.getStatusCode().value();
            failed(event, reasonFor(status), status);
            log.warn("Discord refused the {} webhook with HTTP {}", event.wireName(), status);
        } catch (RestClientException unreachable) {
            failed(event, WebhookFailureReason.UNREACHABLE, NO_HTTP_STATUS);
            log.warn("The {} webhook could not reach Discord ({})", event.wireName(), unreachable.getClass().getSimpleName());
        }
        return false;
    }

    Map<String, Object> payload(WebhookSettings settings, List<DiscordEmbed> embeds, boolean mentionAdmins) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", botName(settings.getBotName()));
        avatarUrl(settings.getAvatarUrl()).ifPresent(avatar -> payload.put("avatar_url", avatar));
        String roleId = settings.getDiscordAdminRoleId() == null ? "" : settings.getDiscordAdminRoleId().trim();
        boolean mentionsRole = mentionAdmins && ROLE_ID.matcher(roleId).matches();
        if (mentionsRole) {
            payload.put("content", "<@&" + roleId + ">");
        }
        payload.put("allowed_mentions", Map.of("parse", List.of(), "roles", mentionsRole ? List.of(roleId) : List.of()));
        payload.put("embeds", embeds.stream().map(DiscordEmbed::toPayload).toList());
        return payload;
    }

    Optional<String> avatarUrl(@Nullable String configured) {
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        String avatar = configured.trim();
        if (avatar.startsWith("/") && !avatar.startsWith("//")) {
            avatar = publicUrls.origin() + avatar;
        }
        return isAbsoluteHttpUrl(avatar) ? Optional.of(avatar) : Optional.empty();
    }

    private static boolean isAbsoluteHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String botName(@Nullable String configured) {
        return configured == null || configured.isBlank() ? DEFAULT_BOT_NAME : configured.trim();
    }

    private static WebhookFailureReason reasonFor(int status) {
        if (status == TOO_MANY_REQUESTS) {
            return WebhookFailureReason.RATE_LIMITED;
        }
        return status >= FIRST_SERVER_ERROR ? WebhookFailureReason.DISCORD_ERROR : WebhookFailureReason.REJECTED;
    }

    private void failed(WebhookEvent event, WebhookFailureReason reason, int httpStatus) {
        deliveryStatus.failed(new WebhookDeliveryFailure(clock.instant(), event, reason, httpStatus));
    }
}
