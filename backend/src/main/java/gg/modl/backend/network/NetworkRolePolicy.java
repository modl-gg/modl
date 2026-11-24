package gg.modl.backend.network;

import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.Topic;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class NetworkRolePolicy {
    public static final String MEMBER_KEY_AS_CORE =
        "This server key is for a server behind a proxy; set mode: behind-proxy, or create a proxy or standalone key";
    private static final String MEMBER_ROLE_NAME = "MEMBER";
    private static final List<Topic> CORE_TOPICS = List.of(
        Topic.TOPIC_MINECRAFT_PERMISSIONS,
        Topic.TOPIC_MINECRAFT_PUNISHMENT_TYPES,
        Topic.TOPIC_MINECRAFT_STAFF_NOTIFICATIONS,
        Topic.TOPIC_MINECRAFT_PUNISHMENTS,
        Topic.TOPIC_MINECRAFT_PLAYER_NOTIFICATIONS,
        Topic.TOPIC_MINECRAFT_STAFF_2FA,
        Topic.TOPIC_NETWORK_MEMBER_ACTIONS,
        Topic.TOPIC_NETWORK_REPLAY_CAPTURE,
        Topic.TOPIC_NETWORK_ROSTER);
    private static final List<Topic> MEMBER_TOPICS = List.of(
        Topic.TOPIC_NETWORK_MODERATION,
        Topic.TOPIC_NETWORK_REPLAY_CAPTURE);

    private NetworkRolePolicy() {
    }

    public static ServerRole requestedRole(@Nullable String roleName) {
        return roleName != null && MEMBER_ROLE_NAME.equals(roleName.trim().toUpperCase(Locale.ROOT))
            ? ServerRole.SERVER_ROLE_MEMBER
            : ServerRole.SERVER_ROLE_CORE;
    }

    public static ServerRole requestedRole(ServerRole requested) {
        return requested == ServerRole.SERVER_ROLE_MEMBER ? ServerRole.SERVER_ROLE_MEMBER : ServerRole.SERVER_ROLE_CORE;
    }

    public static Optional<ServerRole> admittedRole(MinecraftCredential credential, ServerRole requested) {
        ServerRole role = requestedRole(requested);
        return credential.isMemberOnly() && role != ServerRole.SERVER_ROLE_MEMBER ? Optional.empty() : Optional.of(role);
    }

    public static List<Topic> topicsFor(ServerRole role) {
        return role == ServerRole.SERVER_ROLE_MEMBER ? MEMBER_TOPICS : CORE_TOPICS;
    }

    public static boolean grants(ServerRole role, Topic topic) {
        return topicsFor(role).contains(topic);
    }
}
