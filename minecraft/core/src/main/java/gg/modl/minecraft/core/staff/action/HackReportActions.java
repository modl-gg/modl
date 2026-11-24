package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.request.CreateTicketRequest;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.service.TicketKind;
import gg.modl.minecraft.core.service.TicketService;
import gg.modl.minecraft.core.util.Futures;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.command.CommandActor;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static gg.modl.minecraft.core.util.Java8Collections.listOf;

@RequiredArgsConstructor
public final class HackReportActions {
    public static final StaffRequirement REQUIREMENT = StaffRequirement.builder()
            .playerOnly(true)
            .build();

    private static final String TICKET_TYPE = "player";

    private final StaffActionContext context;
    private final TicketService tickets;
    private final Supplier<@Nullable ReplayService> replays;

    public void report(StaffActor actor, AbstractPlayer target, @Nullable String details) {
        CommandActor commandActor = actor.commandActor();
        if (tickets.checkCooldown(commandActor, TICKET_TYPE)) return;

        Platform platform = context.getPlatform();
        AbstractPlayer reporter = platform.getAbstractPlayer(actor.uuid(), false);
        if (reporter == null) {
            context.reply(actor, "general.player_not_found");
            return;
        }
        if (tickets.denySelfReport(commandActor, reporter, target)) return;

        String description = details != null && !details.isEmpty() ? details : null;
        String createdServer = platform.getPlayerServer(actor.uuid());
        Futures.whenComplete(captureReplay(target), (replayUrl, failure) -> {
            CreateTicketRequest request = CreateTicketRequest.builder()
                    .creatorUuid(reporter.getUuid().toString())
                    .type(TICKET_TYPE)
                    .creatorName(reporter.getUsername())
                    .subject("Cheating: " + target.getUsername())
                    .description(description)
                    .reportedPlayerUuid(target.getUuid().toString())
                    .reportedPlayerName(target.getUsername())
                    .priority("normal")
                    .createdServer(createdServer)
                    .tags(listOf("report", "cheating"))
                    .replayUrl(failure == null ? replayUrl : null)
                    .build();
            tickets.submitFinishedTicket(commandActor, request, TicketKind.REPORT, TICKET_TYPE);
        }, platform.getLogger());
    }

    private CompletableFuture<@Nullable String> captureReplay(AbstractPlayer target) {
        ReplayService replayService = replays.get();
        if (replayService == null || !replayService.shouldAttemptCapture(target.getUuid())) {
            return CompletableFuture.completedFuture(null);
        }
        return replayService.captureReplay(target.getUuid(), target.getUsername());
    }
}
