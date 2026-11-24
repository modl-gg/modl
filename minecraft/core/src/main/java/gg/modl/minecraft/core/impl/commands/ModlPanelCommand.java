package gg.modl.minecraft.core.impl.commands;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.request.PanelAccountLinkRequest;
import gg.modl.minecraft.api.http.request.PanelLoginLinkRequest;
import gg.modl.minecraft.api.http.response.PanelLoginLinkResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.command.Staff2faGated;
import gg.modl.minecraft.core.config.ConfigManager;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.StaffPermissionService;
import gg.modl.minecraft.core.util.ClickableJsonMessage;
import lombok.RequiredArgsConstructor;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.command.CommandActor;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor @Command("modl")
public class ModlPanelCommand {
    private static final Pattern LINK_CODE = Pattern.compile("[A-Za-z0-9-]{4,64}");
    private static final int BAD_REQUEST = 400, NOT_FOUND = 404, CONFLICT = 409, TOO_MANY_REQUESTS = 429;
    private static final long SECONDS_PER_MINUTE = 60;

    private final Platform platform;
    private final HttpClientHolder httpClientHolder;
    private final LocaleManager localeManager;
    private final ConfigManager configManager;
    private final StaffPermissionService staffPermissionService;

    @Subcommand("link")
    @Description("Link your Minecraft account to your panel account")
    @PlayerOnly
    public void link(CommandActor actor, String code) {
        String trimmedCode = code.trim();
        if (!LINK_CODE.matcher(trimmedCode).matches()) {
            actor.reply(localeManager.getMessage("panel_account.link_invalid_code"));
            return;
        }

        UUID playerUuid = actor.uniqueId();
        httpClientHolder.getClient()
                .linkPanelAccount(new PanelAccountLinkRequest(playerUuid.toString(), playerName(actor), trimmedCode))
                .thenAccept(response -> {
                    if (!response.isLinked()) {
                        reply(actor, "panel_account.link_error");
                        return;
                    }
                    staffPermissionService.reload();
                    reply(actor, "panel_account.link_success");
                })
                .exceptionally(failure -> {
                    reply(actor, linkFailureKey(statusOf(failure)));
                    return null;
                });
    }

    @Subcommand("panel")
    @Description("Get a one-time login link for the panel")
    @PlayerOnly @Staff2faGated
    public void panel(CommandActor actor) {
        UUID playerUuid = actor.uniqueId();
        if (!platform.isOnlineModeAuthenticated(playerUuid) && !configManager.getPanelLoginConfig().isAllowOfflineMode()) {
            actor.reply(localeManager.getMessage("panel_account.login_offline_mode"));
            return;
        }

        httpClientHolder.getClient()
                .createPanelLoginLink(new PanelLoginLinkRequest(playerUuid.toString()))
                .thenAccept(response -> platform.runOnMainThread(() -> sendLoginLink(playerUuid, response)))
                .exceptionally(failure -> {
                    reply(actor, loginFailureKey(statusOf(failure)));
                    return null;
                });
    }

    private void sendLoginLink(UUID playerUuid, PanelLoginLinkResponse response) {
        String json = ClickableJsonMessage.text(localeManager.getMessage("panel_account.login_link"))
                .color("green")
                .bold(true)
                .underlined(true)
                .openUrl(response.getUrl())
                .hoverText(localeManager.getMessage("panel_account.login_link_hover",
                        mapOf("minutes", String.valueOf(expiryMinutes(response.getExpiresInSeconds())))))
                .toJson();
        platform.sendJsonMessage(playerUuid, json);
    }

    static long expiryMinutes(long expiresInSeconds) {
        return Math.max(1, (expiresInSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE);
    }

    static String linkFailureKey(int status) {
        if (status == BAD_REQUEST || status == NOT_FOUND) return "panel_account.link_invalid_code";
        if (status == CONFLICT) return "panel_account.link_already_linked";
        if (status == TOO_MANY_REQUESTS) return "panel_account.rate_limited";
        return "panel_account.link_error";
    }

    static String loginFailureKey(int status) {
        if (status == NOT_FOUND) return "panel_account.login_not_linked";
        if (status == CONFLICT) return "panel_account.login_not_online";
        if (status == TOO_MANY_REQUESTS) return "panel_account.rate_limited";
        return "panel_account.login_error";
    }

    private static int statusOf(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause instanceof ApiClientException ? ((ApiClientException) cause).getStatusCode() : 0;
    }

    private String playerName(CommandActor actor) {
        AbstractPlayer player = platform.getPlayer(actor.uniqueId());
        return player != null ? player.getUsername() : actor.name();
    }

    private void reply(CommandActor actor, String localeKey) {
        String message = localeManager.getMessage(localeKey);
        platform.runOnMainThread(() -> actor.reply(message));
    }
}
