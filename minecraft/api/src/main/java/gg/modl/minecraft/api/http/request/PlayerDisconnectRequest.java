package gg.modl.minecraft.api.http.request;

import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class PlayerDisconnectRequest {
    String minecraftUuid;
    long sessionDurationMs;
    @Nullable String serverInstanceId;
}
