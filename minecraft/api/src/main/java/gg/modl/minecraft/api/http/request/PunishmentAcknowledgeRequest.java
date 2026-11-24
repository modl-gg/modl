package gg.modl.minecraft.api.http.request;

import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class PunishmentAcknowledgeRequest {
    String punishmentId, playerUuid, executedAt;
    @Nullable String errorMessage;
    boolean success;
}
