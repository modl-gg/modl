package gg.modl.backend.ticket.repository;

import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.ToString;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@Builder
public class TicketSummary {
    String id;
    TicketCategory type;
    @Nullable String subject;
    TicketStatus status;
    TicketPriority priority;
    @Nullable String creatorUuid;
    @Nullable String creatorName;
    @Nullable String reportedPlayer;
    @Nullable String reportedPlayerUuid;
    List<String> tags;
    List<String> assignedTo;
    boolean locked;
    boolean hidden;
    @Nullable AppealWorkflowStatus appealWorkflowStatus;
    @Nullable String appealPunishmentId;
    @Nullable String replayId;
    @ToString.Exclude
    String publicAccessKey;
    int replyCount;
    @Nullable TicketReply lastReply;
    Instant createdAt;
    Instant updatedAt;
    int version;
}
