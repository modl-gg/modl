package gg.modl.minecraft.features.reporter;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@Builder
public class TicketRequest {
    String creatorUuid;
    String creatorName;
    String type;
    String subject;
    String description;
    String reportedPlayerUuid;
    String reportedPlayerName;
    String priority;
    String createdServer;
    @Nullable String replayUrl;
}
