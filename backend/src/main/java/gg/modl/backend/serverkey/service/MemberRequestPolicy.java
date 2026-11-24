package gg.modl.backend.serverkey.service;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.replay.util.ReplayReferenceUtil;
import gg.modl.backend.serverkey.ServerKeyErrors;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.dto.request.MinecraftCreateTicketRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberRequestPolicy {
    static final String TICKET_REFUSED =
        "A server behind a proxy may only file anticheat reports about one of its own players";
    static final String REPLAY_REFUSED =
        "A server behind a proxy may only attach a replay of the player it reports";

    private final ReplayRepository replays;

    public MinecraftCreateTicketRequest admitTicket(HttpServletRequest httpRequest, MinecraftCreateTicketRequest request) {
        Optional<MinecraftCredential> member = memberCredential(httpRequest);
        if (member.isEmpty()) {
            return request;
        }
        if (!isAnticheatReport(request)) {
            throw new ForbiddenException(TICKET_REFUSED, ServerKeyErrors.SCOPE);
        }
        if (request.replayUrl() != null && !isReplayOfReportedPlayer(request.replayUrl(), request.reportedPlayerUuid())) {
            throw new ForbiddenException(REPLAY_REFUSED, ServerKeyErrors.SCOPE);
        }
        return new MinecraftCreateTicketRequest(
            request.creatorUuid(),
            request.creatorName(),
            request.type(),
            request.subject(),
            request.description(),
            request.reportedPlayerUuid(),
            request.reportedPlayerName(),
            null,
            List.of(),
            TicketPriority.NORMAL.getId(),
            member.get().keyName(),
            request.replayUrl()
        );
    }

    private boolean isReplayOfReportedPlayer(String replayUrl, @Nullable String reportedPlayerUuid) {
        String replayId = ReplayReferenceUtil.extractReplayId(replayUrl);
        if (replayId == null || reportedPlayerUuid == null) {
            return false;
        }
        String reported = UuidUtils.normalize(reportedPlayerUuid);
        return replays.findById(replayId)
            .map(replay -> replay.getTargetUuid() != null && UuidUtils.normalize(replay.getTargetUuid()).equals(reported))
            .orElse(false);
    }

    private static boolean isAnticheatReport(MinecraftCreateTicketRequest request) {
        return isPlayerReport(request.type())
            && request.reportedPlayerUuid() != null
            && request.reportedPlayerUuid().equalsIgnoreCase(request.creatorUuid());
    }

    private static boolean isPlayerReport(String type) {
        try {
            return TicketCategory.fromCanonicalId(type) == TicketCategory.PLAYER;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    private static Optional<MinecraftCredential> memberCredential(HttpServletRequest httpRequest) {
        return httpRequest.getAttribute(RequestAttribute.MINECRAFT_CREDENTIAL) instanceof MinecraftCredential credential
            && credential.isMemberOnly()
            ? Optional.of(credential)
            : Optional.empty();
    }
}
