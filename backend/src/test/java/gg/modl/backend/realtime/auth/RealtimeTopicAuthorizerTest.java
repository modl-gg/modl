package gg.modl.backend.realtime.auth;

import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.network.NetworkRolePolicy;
import gg.modl.backend.role.service.PermissionService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffLookupCache;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.Topic;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RealtimeTopicAuthorizerTest {

    @Test
    void panelTicketTopicsRequireReadPermission() {
        PermissionService permissionService = mock(PermissionService.class);
        StaffLookupCache staffLookupCache = mock(StaffLookupCache.class);
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(permissionService, roleAuthorization(permissionService, staffLookupCache));
        Server server = server();

        Staff staff = Staff.builder()
            .email("staff@example.com")
            .roleId("support")
            .build();
        when(staffLookupCache.findByEmail(server, "staff@example.com")).thenReturn(Optional.of(staff));
        when(permissionService.hasPermission(server, "support", "ticket.view.all")).thenReturn(true);

        assertTrue(authorizer.canSubscribe(
            RealtimePrincipal.panel(server, "staff@example.com", "session-id"),
            Topic.TOPIC_PANEL_TICKETS
        ));
    }

    @Test
    void panelPermissionScopesAreEnforcedPerTopic() {
        PermissionService permissionService = mock(PermissionService.class);
        StaffLookupCache staffLookupCache = mock(StaffLookupCache.class);
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(permissionService, roleAuthorization(permissionService, staffLookupCache));
        Server server = server();

        Staff staff = Staff.builder()
            .email("staff@example.com")
            .roleId("support")
            .build();
        when(staffLookupCache.findByEmail(server, "staff@example.com")).thenReturn(Optional.of(staff));
        when(permissionService.hasPermission(server, "support", "ticket.view.all")).thenReturn(true);

        RealtimePrincipal principal = RealtimePrincipal.panel(server, "staff@example.com", "session-id");
        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_PANEL_NOTIFICATIONS));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_PANEL_STAFF));
    }

    @Test
    void anyoneWhoViewsOneSettingsAreaHearsAboutSettingsChanges() {
        PermissionService permissionService = mock(PermissionService.class);
        StaffLookupCache staffLookupCache = mock(StaffLookupCache.class);
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(permissionService, roleAuthorization(permissionService, staffLookupCache));
        Server server = server();
        when(staffLookupCache.findByEmail(server, "storage@example.com"))
            .thenReturn(Optional.of(Staff.builder().email("storage@example.com").roleId("storage").build()));
        when(permissionService.hasAnyPermissionWithPrefix(server, "storage", PermissionService.ADMIN_SETTINGS_VIEW + "."))
            .thenReturn(true);

        assertTrue(authorizer.canSubscribe(RealtimePrincipal.panel(server, "storage@example.com", "session-id"),
            Topic.TOPIC_PANEL_SETTINGS));
        assertFalse(authorizer.canSubscribe(RealtimePrincipal.panel(server, "storage@example.com", "session-id"),
            Topic.TOPIC_PANEL_STAFF));
    }

    @Test
    void playerAndPunishmentTopicsFollowThePlayerViewRule() {
        for (String granted : new String[] {"punishment.view", "punishment.modify", "punishment.apply.7"}) {
            PermissionService permissionService = mock(PermissionService.class);
            StaffLookupCache staffLookupCache = mock(StaffLookupCache.class);
            RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(permissionService, roleAuthorization(permissionService, staffLookupCache));
            Server server = server();
            when(staffLookupCache.findByEmail(server, "helper@example.com"))
                .thenReturn(Optional.of(Staff.builder().email("helper@example.com").roleId("helper").build()));
            if (granted.startsWith(PermissionService.PUNISHMENT_APPLY_PREFIX)) {
                when(permissionService.hasAnyPermissionWithPrefix(server, "helper", PermissionService.PUNISHMENT_APPLY_PREFIX)).thenReturn(true);
            } else {
                when(permissionService.hasPermission(server, "helper", granted)).thenReturn(true);
            }
            RealtimePrincipal principal = RealtimePrincipal.panel(server, "helper@example.com", "session-id");

            assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_PANEL_PLAYERS), granted);
            assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_PANEL_PUNISHMENTS), granted);
        }
    }

    @Test
    void staffWhoCannotViewPlayersGetNoPlayerOrPunishmentUpdates() {
        PermissionService permissionService = mock(PermissionService.class);
        StaffLookupCache staffLookupCache = mock(StaffLookupCache.class);
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(permissionService, roleAuthorization(permissionService, staffLookupCache));
        Server server = server();
        when(staffLookupCache.findByEmail(server, "support@example.com"))
            .thenReturn(Optional.of(Staff.builder().email("support@example.com").roleId("support").build()));
        when(permissionService.hasPermission(server, "support", "ticket.view.all")).thenReturn(true);
        RealtimePrincipal principal = RealtimePrincipal.panel(server, "support@example.com", "session-id");

        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_PANEL_PLAYERS));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_PANEL_PUNISHMENTS));
    }

    @Test
    void panelCannotSubscribeToMinecraftTopics() {
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(mock(PermissionService.class), mock(RoleAuthorization.class));

        assertFalse(authorizer.canSubscribe(
            RealtimePrincipal.panel(server(), "staff@example.com", "session-id"),
            Topic.TOPIC_MINECRAFT_PRESENCE
        ));
    }

    @Test
    void aCoreCanSubscribeToExactlyTheTopicsItsStartupGrants() {
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(mock(PermissionService.class), mock(RoleAuthorization.class));
        RealtimePrincipal principal = RealtimePrincipal.minecraft(server(), "instance-1");

        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_PERMISSIONS));
        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_PUNISHMENT_TYPES));
        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_STAFF_NOTIFICATIONS));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_PRESENCE));
        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_PUNISHMENTS));
        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_PLAYER_NOTIFICATIONS));
        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_STAFF_2FA));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_STAT_WIPES));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_PANEL_TICKETS));
        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_NETWORK_MEMBER_ACTIONS));
        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_NETWORK_REPLAY_CAPTURE));
        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_NETWORK_ROSTER));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_NETWORK_MODERATION));
    }

    @Test
    void subscriptionsFollowTheSameRoleTopicsAsStartup() {
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(mock(PermissionService.class), mock(RoleAuthorization.class));
        RealtimePrincipal core = RealtimePrincipal.minecraft(server(), "instance-1");
        RealtimePrincipal member = RealtimePrincipal.minecraft(server(), "instance-2",
            new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "k1", "lobby"), ServerRole.SERVER_ROLE_MEMBER, "lobby", "3.0.0");

        for (Topic topic : Topic.values()) {
            if (topic == Topic.UNRECOGNIZED) {
                continue;
            }
            assertEquals(NetworkRolePolicy.topicsFor(ServerRole.SERVER_ROLE_CORE).contains(topic),
                authorizer.canSubscribe(core, topic), topic.name());
            assertEquals(NetworkRolePolicy.topicsFor(ServerRole.SERVER_ROLE_MEMBER).contains(topic),
                authorizer.canSubscribe(member, topic), topic.name());
        }
    }

    @Test
    void aMemberSeesOnlyTheModerationStateAndCaptureRequests() {
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(mock(PermissionService.class), mock(RoleAuthorization.class));
        RealtimePrincipal member = RealtimePrincipal.minecraft(server(), "instance-2",
            new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "k1", "lobby"), ServerRole.SERVER_ROLE_MEMBER, "lobby", "3.0.0");

        assertTrue(authorizer.canSubscribe(member, Topic.TOPIC_NETWORK_MODERATION));
        assertTrue(authorizer.canSubscribe(member, Topic.TOPIC_NETWORK_REPLAY_CAPTURE));
        for (Topic topic : new Topic[] {Topic.TOPIC_MINECRAFT_PUNISHMENTS, Topic.TOPIC_MINECRAFT_PERMISSIONS,
            Topic.TOPIC_MINECRAFT_STAT_WIPES, Topic.TOPIC_NETWORK_MEMBER_ACTIONS, Topic.TOPIC_NETWORK_ROSTER}) {
            assertFalse(authorizer.canSubscribe(member, topic), topic.name());
        }
    }

    private Server server() {
        Server server = TestServers.server("server", "admin@example.com");
        server.setId("server-id");
        return server;
    }

    private static RoleAuthorization roleAuthorization(PermissionService permissionService,
                                                      StaffLookupCache staffLookupCache) {
        return new RoleAuthorization(permissionService, mock(StaffRepository.class), staffLookupCache);
    }
}
