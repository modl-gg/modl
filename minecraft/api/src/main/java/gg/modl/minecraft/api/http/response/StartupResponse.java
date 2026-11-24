package gg.modl.minecraft.api.http.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Getter @NoArgsConstructor @AllArgsConstructor
public class StartupResponse {
    private String panelUrl;
    private String timestamp;
    private String serverInstanceId;
    private Boolean realtimeEnabled;
    private Integer realtimeProtocolVersion;
    private List<String> realtimeTopics;
    private @Nullable Boolean networkHub;
    private @Nullable String modlVersion;
    private @Nullable String latestVersion;
    private @Nullable String latestReleaseUrl;

    public StartupResponse(String panelUrl, String timestamp, String serverInstanceId, Boolean realtimeEnabled,
                           Integer realtimeProtocolVersion, List<String> realtimeTopics) {
        this(panelUrl, timestamp, serverInstanceId, realtimeEnabled, realtimeProtocolVersion, realtimeTopics, null);
    }

    public StartupResponse(String panelUrl, String timestamp, String serverInstanceId, Boolean realtimeEnabled,
                           Integer realtimeProtocolVersion, List<String> realtimeTopics, @Nullable Boolean networkHub) {
        this(panelUrl, timestamp, serverInstanceId, realtimeEnabled, realtimeProtocolVersion, realtimeTopics, networkHub, null, null, null);
    }

    public boolean runsNetworkHub() {
        return Boolean.TRUE.equals(networkHub);
    }

    public boolean enablesRealtime() {
        return Boolean.TRUE.equals(realtimeEnabled);
    }
}
