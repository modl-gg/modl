package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.server.connection.ConnectedServerRegistry;
import gg.modl.backend.server.connection.ServerStartupReport;
import gg.modl.backend.serverkey.ServerKeyErrors;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.Topic;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.check.LatestRelease;
import gg.modl.backend.update.check.PluginReleaseNotice;
import gg.modl.update.ReleaseVersion;
import java.util.Optional;

class MinecraftStartupServiceTest {
    private static final ServerStartupReport REPORT =
        ServerStartupReport.of("3.0.0", "SPIGOT", "1.21.11", ServerRole.SERVER_ROLE_CORE, null);
    private static final ServerStartupReport MEMBER_REPORT =
        ServerStartupReport.of("3.0.0", "SPIGOT", "1.21.11", ServerRole.SERVER_ROLE_MEMBER, "lobby");
    private static final MinecraftCredential MEMBER_KEY = new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "key-1", "lobby");

    private static final String RELEASE_PAGE = "https://github.com/modl-gg/modl/releases/tag/v3.1.0";

    private final ConnectedServerRegistry registry = mock(ConnectedServerRegistry.class);

    @Test
    void aCoreGetsTheSyncTopicsAndTheCoreNetworkTopics() {
        MinecraftStartupService service = service(properties(true));

        Map<String, Object> response = service.handleStartup(MinecraftCredential.instanceKey(), REPORT);

        assertEquals("https://panel.example.com", response.get("panelUrl"));
        assertNotNull(response.get("serverInstanceId"));
        assertTrue(((String) response.get("serverInstanceId")).length() >= 36);
        assertEquals(true, response.get("realtimeEnabled"));
        assertEquals(true, response.get("networkHub"));
        assertEquals("3.0.2", response.get("modlVersion"));
        assertEquals("3.1.0", response.get("latestVersion"));
        assertEquals(RELEASE_PAGE, response.get("latestReleaseUrl"));
        assertEquals("", response.get("realtimeUrl"));
        assertEquals(1, response.get("realtimeProtocolVersion"));
        assertIterableEquals(
            List.of(
                Topic.TOPIC_MINECRAFT_PERMISSIONS.name(),
                Topic.TOPIC_MINECRAFT_PUNISHMENT_TYPES.name(),
                Topic.TOPIC_MINECRAFT_STAFF_NOTIFICATIONS.name(),
                Topic.TOPIC_MINECRAFT_PUNISHMENTS.name(),
                Topic.TOPIC_MINECRAFT_PLAYER_NOTIFICATIONS.name(),
                Topic.TOPIC_MINECRAFT_STAFF_2FA.name(),
                Topic.TOPIC_NETWORK_MEMBER_ACTIONS.name(),
                Topic.TOPIC_NETWORK_REPLAY_CAPTURE.name(),
                Topic.TOPIC_NETWORK_ROSTER.name()
            ),
            (List<?>) response.get("realtimeTopics")
        );
    }

    @Test
    void aMemberGetsOnlyTheMemberNetworkTopics() {
        Map<String, Object> response = service(properties(true)).handleStartup(MEMBER_KEY, MEMBER_REPORT);

        assertIterableEquals(
            List.of(Topic.TOPIC_NETWORK_MODERATION.name(), Topic.TOPIC_NETWORK_REPLAY_CAPTURE.name()),
            (List<?>) response.get("realtimeTopics"));
    }

    @Test
    void aBehindProxyKeyCannotStartACore() {
        ForbiddenException refused = assertThrows(ForbiddenException.class,
            () -> service(properties(true)).handleStartup(MEMBER_KEY, REPORT));

        assertEquals(ServerKeyErrors.SCOPE, refused.getMachineCode());
        verify(registry, never()).recordStartup(anyString(), any());
    }

    @Test
    void startupOmitsTopicsAndTheHubWhenRealtimeIsDisabled() {
        MinecraftStartupService service = service(properties(false));

        Map<String, Object> response = service.handleStartup(MinecraftCredential.instanceKey(), REPORT);

        assertEquals(false, response.get("realtimeEnabled"));
        assertEquals(false, response.get("networkHub"));
        assertIterableEquals(List.of(), (List<?>) response.get("realtimeTopics"));
    }

    @Test
    void theStartingServerIsRegisteredUnderTheInstanceIdItIsGiven() {
        Map<String, Object> response = service(properties(true)).handleStartup(MinecraftCredential.instanceKey(), REPORT);

        verify(registry).recordStartup((String) response.get("serverInstanceId"), REPORT);
    }

    private MinecraftStartupService service(RealtimeProperties realtimeProperties) {
        PublicUrls publicUrls = mock(PublicUrls.class);
        when(publicUrls.panel()).thenReturn("https://panel.example.com");
        LatestRelease latestRelease = mock(LatestRelease.class);
        when(latestRelease.pluginNotice()).thenReturn(Optional.of(new PluginReleaseNotice("3.1.0", RELEASE_PAGE)));
        return new MinecraftStartupService(publicUrls, realtimeProperties, registry,
            ReleaseBuild.of(ReleaseVersion.parse("3.0.2"), ReleaseVersion.parse("3.0.0")), latestRelease, Clock.systemUTC());
    }

    private RealtimeProperties properties(boolean enabled) {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setEnabled(enabled);
        properties.setProtocolVersion(1);
        return properties;
    }
}
