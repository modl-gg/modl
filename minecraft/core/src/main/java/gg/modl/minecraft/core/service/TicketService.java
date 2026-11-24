package gg.modl.minecraft.core.service;

import org.jspecify.annotations.Nullable;
import revxrsal.commands.command.CommandActor;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.api.http.request.CreateTicketRequest;
import gg.modl.minecraft.api.http.response.CreateTicketResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.CommandUtil;
import gg.modl.minecraft.core.util.ClickableJsonMessage;
import gg.modl.minecraft.core.util.ApiFailures;
import lombok.RequiredArgsConstructor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import static gg.modl.minecraft.core.util.Java8Collections.listOf;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
public class TicketService {
    private static final long COOLDOWN_MS = 60_000;

    private final Cache cache;
    private final HttpClientHolder httpClientHolder;
    private final Platform platform;
    private final LocaleManager localeManager;

    public boolean checkCooldown(CommandActor actor, String ticketType) {
        UUID uuid = actor.uniqueId();
        CachedProfile profile = cache.getPlayerProfile(uuid);
        if (profile == null) return false;

        String cooldownKey = "ticket:" + ticketType;
        if (!profile.getCooldowns().isOnCooldown(cooldownKey, COOLDOWN_MS)) return false;

        long remainingMs = profile.getCooldowns().getRemainingMs(cooldownKey, COOLDOWN_MS);
        long remainingSeconds = remainingMs / 1000;
        actor.reply(localeManager.getMessage("messages.ticket_cooldown",
                mapOf("seconds", String.valueOf(remainingSeconds))));
        return true;
    }

    public void setCooldown(UUID uuid, String cooldownType) {
        CachedProfile profile = cache.getPlayerProfile(uuid);
        if (profile != null) profile.getCooldowns().set("ticket:" + cooldownType);
    }

    public void submitPlayerFormTicket(CommandActor actor, String ticketType, TicketKind kind, @Nullable String titlePrefix) {
        if (checkCooldown(actor, ticketType)) return;

        AbstractPlayer player = platform.getAbstractPlayer(actor.uniqueId(), false);
        if (player == null) {
            actor.reply(localeManager.getMessage("general.player_not_found"));
            return;
        }
        String createdServer = platform.getPlayerServer(actor.uniqueId());
        String title = titlePrefix == null ? null : titlePrefix + player.getUsername();

        CreateTicketRequest request = CreateTicketRequest.builder()
            .creatorUuid(player.getUuid().toString())
            .type(ticketType)
            .creatorName(player.getUsername())
            .subject(title)
            .priority("normal")
            .createdServer(createdServer)
            .tags(listOf())
            .build();

        submitUnfinishedTicket(actor, request, kind, ticketType);
    }

    public void submitFinishedTicket(CommandActor actor, CreateTicketRequest request, TicketKind kind, String cooldownType) {
        submitTicket(actor, kind, cooldownType, () -> httpClientHolder.getClient().createTicket(request), true);
    }

    public void submitUnfinishedTicket(CommandActor actor, CreateTicketRequest request, TicketKind kind, String cooldownType) {
        submitTicket(actor, kind, cooldownType, () -> httpClientHolder.getClient().createUnfinishedTicket(request), false);
    }

    public boolean denySelfReport(CommandActor actor, AbstractPlayer reporter, AbstractPlayer targetPlayer) {
        if (reporter == null || targetPlayer == null) return false;
        if (!reporter.getUuid().equals(targetPlayer.getUuid())) return false;

        actor.reply(localeManager.getMessage("messages.cannot_report_self"));
        return true;
    }

    private void submitTicket(CommandActor actor, TicketKind kind, String cooldownType,
                              Supplier<CompletableFuture<CreateTicketResponse>> submitter, boolean finished) {
        String startKey = finished ? "messages.submitting" : "messages.creating";
        actor.reply(localeManager.getMessage(startKey, mapOf("type", kind.inlineName(localeManager))));

        CompletableFuture<CreateTicketResponse> future = submitter.get();
        future.thenAccept(response -> {
            String ticketId = response.getTicketId();
            if (response.isSuccess() && ticketId != null) {
                handleTicketSuccess(actor, ticketId, response.getPublicUrl(), kind, cooldownType, finished);
            } else {
                handleTicketFailure(actor, kind, response.getMessage(), finished);
            }
        }).exceptionally(throwable -> {
            if (throwable.getCause() instanceof PanelUnavailableException) {
                actor.reply(ApiFailures.unavailable(localeManager, (PanelUnavailableException) throwable.getCause()));
            } else {
                handleTicketFailure(actor, kind, throwable.getMessage(), finished);
            }
            return null;
        });
    }

    private void handleTicketSuccess(CommandActor actor, String ticketId, @Nullable String publicUrl, TicketKind kind,
                                     String cooldownType, boolean finished) {
        setCooldown(actor.uniqueId(), cooldownType);

        String successKey = finished ? "messages.success" : "messages.created";
        actor.reply(localeManager.getMessage(successKey, mapOf("type", kind.displayName(localeManager))));
        actor.reply(localeManager.getMessage("messages.ticket_id", mapOf("ticketId", ticketId)));

        String label = finished
                ? localeManager.getMessage("messages.view_ticket_label")
                : localeManager.getMessage("messages.complete_form_label", mapOf("type", kind.inlineName(localeManager)));
        sendTicketLink(actor, label, ticketId, publicUrl);
    }

    private void handleTicketFailure(CommandActor actor, TicketKind kind, @Nullable String errorMessage, boolean finished) {
        String failureKey = finished ? "messages.failed_submit" : "messages.failed_create";
        actor.reply(localeManager.getMessage(failureKey,
                mapOf("type", kind.inlineName(localeManager), "error", localeManager.sanitizeErrorMessage(errorMessage))));
        actor.reply(localeManager.getMessage("messages.try_again"));
    }

    public void sendTicketLink(CommandActor actor, String label, String ticketId, @Nullable String publicUrl) {
        if (publicUrl == null || publicUrl.isEmpty()) return;
        if (CommandUtil.isConsole(actor)) {
            actor.reply(localeManager.getMessage("messages.console_ticket_url", mapOf("message", label, "url", publicUrl)));
            return;
        }
        sendTicketLink(actor.uniqueId(), label, ticketId, publicUrl);
    }

    public void sendTicketLink(UUID playerUuid, String label, String ticketId, @Nullable String publicUrl) {
        if (publicUrl == null || publicUrl.isEmpty()) return;
        String clickText = localeManager.getMessage("messages.click_to_view");
        String hoverText = localeManager.getMessage("messages.click_to_view_hover", mapOf("ticketId", ticketId));
        String json = ClickableJsonMessage.empty()
                .extra(ClickableJsonMessage.text("").color("gold"))
                .extra(ClickableJsonMessage.text(label + ": ").color("gray"))
                .extra(ClickableJsonMessage.text(clickText)
                        .color("aqua")
                        .underlined(true)
                        .openUrl(publicUrl)
                        .hoverText(hoverText))
                .toJson();
        platform.runOnMainThread(() -> platform.sendJsonMessage(playerUuid, json));
    }
}
