package gg.modl.minecraft.api.http.request;

import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class StatWipeAcknowledgeRequest {
    String punishmentId;
    @Nullable String serverName;
    boolean success;
}
