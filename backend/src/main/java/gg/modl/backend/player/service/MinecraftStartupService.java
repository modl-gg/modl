package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.network.NetworkRolePolicy;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.server.connection.ConnectedServerRegistry;
import gg.modl.backend.server.connection.ServerStartupReport;
import gg.modl.backend.serverkey.ServerKeyErrors;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.Topic;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.check.LatestRelease;
import gg.modl.backend.update.check.PluginReleaseNotice;

@Service
@RequiredArgsConstructor
public class MinecraftStartupService {
    private static final String DERIVED_REALTIME_URL = "";

    private final PublicUrls publicUrls;
    private final RealtimeProperties realtimeProperties;
    private final ConnectedServerRegistry connectedServerRegistry;
    private final ReleaseBuild releaseBuild;
    private final LatestRelease latestRelease;
    private final Clock clock;

    public Map<String, Object> handleStartup(MinecraftCredential credential, ServerStartupReport report) {
        ServerRole role = NetworkRolePolicy.admittedRole(credential, report.role())
            .orElseThrow(() -> new ForbiddenException(NetworkRolePolicy.MEMBER_KEY_AS_CORE, ServerKeyErrors.SCOPE));
        Instant now = clock.instant();
        String serverInstanceId = UUID.randomUUID().toString();
        connectedServerRegistry.recordStartup(serverInstanceId, report);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("panelUrl", publicUrls.panel());
        result.put("timestamp", now.toString());
        result.put("serverInstanceId", serverInstanceId);
        result.put("realtimeEnabled", realtimeProperties.isEnabled());
        result.put("realtimeUrl", DERIVED_REALTIME_URL);
        result.put("realtimeProtocolVersion", realtimeProperties.getProtocolVersion());
        result.put("realtimeTopics", realtimeProperties.isEnabled() ? topicNames(role) : List.of());
        result.put("networkHub", realtimeProperties.isEnabled());
        result.put("modlVersion", releaseBuild.versionText());
        Optional<PluginReleaseNotice> release = latestRelease.pluginNotice();
        result.put("latestVersion", release.map(PluginReleaseNotice::version).orElse(null));
        result.put("latestReleaseUrl", release.map(PluginReleaseNotice::releaseUrl).orElse(null));
        return result;
    }

    private static List<String> topicNames(ServerRole role) {
        return NetworkRolePolicy.topicsFor(role).stream()
            .map(Topic::name)
            .toList();
    }
}
