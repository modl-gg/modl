package gg.modl.minecraft.core.impl.commands.player;

import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.command.CommandActor;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.request.ClaimTicketRequest;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.service.TicketService;
import gg.modl.minecraft.core.locale.LocaleManager;
import lombok.RequiredArgsConstructor;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
public class ClaimTicketCommand {
    private final Platform platform;
    private final HttpClientHolder httpClientHolder;
    private final LocaleManager localeManager;
    private final TicketService ticketUtil;

    @Command("tclaim")
    @Description("Link an unlinked ticket to your account")
    @PlayerOnly
    public void claimTicket(CommandActor actor, String ticketId, String accessKey) {
        AbstractPlayer player = platform.getAbstractPlayer(actor.uniqueId(), false);
        if (player == null) {
            actor.reply(localeManager.getMessage("general.player_not_found"));
            return;
        }

        actor.reply(localeManager.getMessage("messages.claiming_ticket", mapOf("ticketId", ticketId)));

        ClaimTicketRequest request = new ClaimTicketRequest(
            ticketId,
            player.getUuid().toString(),
            player.getUsername(),
            accessKey.trim()
        );

        httpClientHolder.getClient().claimTicket(request).thenAccept(response -> {
            if (response.isSuccess()) {
                actor.reply(localeManager.getMessage("messages.ticket_claimed_success",
                    mapOf("ticketId", ticketId, "subject", response.getSubject() != null ? response.getSubject() : localeManager.getUnknownWord())));

                ticketUtil.sendTicketLink(actor, localeManager.getMessage("messages.view_ticket_label"), ticketId,
                        response.getPublicUrl());
            } else actor.reply(localeManager.getMessage("messages.ticket_claim_failed",
                    mapOf("error", localeManager.sanitizeErrorMessage(response.getMessage()))));
        }).exceptionally(throwable -> {
            String errorMessage = throwable.getMessage();
            if (throwable.getCause() != null) errorMessage = throwable.getCause().getMessage();
            actor.reply(localeManager.getMessage("messages.ticket_claim_failed",
                mapOf("error", localeManager.sanitizeErrorMessage(errorMessage))));
            return null;
        });
    }
}
