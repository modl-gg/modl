package gg.modl.minecraft.api.http.request;

import com.google.gson.annotations.SerializedName;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class PlayerLoginRequest {
    @SerializedName("minecraftUUID")
    String minecraftUuid;
    String username;
    @SerializedName("ip")
    @Nullable String ipAddress;
    @Nullable String skinHash, serverName;
    @Nullable String serverInstanceId;
}
