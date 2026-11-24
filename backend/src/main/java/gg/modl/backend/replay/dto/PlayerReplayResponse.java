package gg.modl.backend.replay.dto;

import gg.modl.backend.ticket.data.Ticket;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record PlayerReplayResponse(
    @Nullable String replayId,
    @Nullable String targetUuid,
    @Nullable String targetName,
    @Nullable String mcVersion,
    long fileSize,
    Instant createdAt,
    @Nullable String status,
    @Nullable String replayUrl,
    MatchSource matchSource
) {
    public enum MatchSource {
        DIRECT_METADATA,
        TICKET_FALLBACK
    }

    public static PlayerReplayResponse fromTicket(Ticket ticket, @Nullable String replayUrl, @Nullable String replayId) {
        return new PlayerReplayResponse(
            replayId,
            firstNonBlank(ticket.getReportedPlayerUuid(), ticket.getCreatorUuid()),
            firstNonBlank(ticket.getReportedPlayer(), ticket.getCreatorName()),
            null,
            0L,
            ticket.getCreated(),
            null,
            replayUrl,
            MatchSource.TICKET_FALLBACK
        );
    }

    public String deduplicationKey() {
        if (replayId != null && !replayId.isBlank()) {
            return idKey(replayId);
        }
        return replayUrl != null ? urlKey(replayUrl) : "empty";
    }

    public static String idKey(String replayId) {
        return "id:" + replayId;
    }

    public static String urlKey(String replayUrl) {
        return "url:" + replayUrl;
    }

    private static @Nullable String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
