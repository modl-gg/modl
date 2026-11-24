package gg.modl.minecraft.api.http.request;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Value @Builder
public class CreateTicketRequest {
    String creatorUuid, type;
    @Nullable String creatorName, subject, description, reportedPlayerUuid, reportedPlayerName, priority, createdServer;
    @Nullable List<String> chatMessages, tags;
    @Nullable String replayUrl;
}
