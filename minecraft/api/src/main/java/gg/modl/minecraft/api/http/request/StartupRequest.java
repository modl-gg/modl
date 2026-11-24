package gg.modl.minecraft.api.http.request;

import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.With;

@Value
@AllArgsConstructor
public class StartupRequest {
    String pluginVersion;
    String platformType;
    String serverVersion;
    int maxPlayers;
    @With ServerRole serverRole;

    public StartupRequest(String pluginVersion, String platformType, String serverVersion, int maxPlayers) {
        this(pluginVersion, platformType, serverVersion, maxPlayers, ServerRole.CORE);
    }

    public enum ServerRole {
        CORE,
        MEMBER
    }
}
