package gg.modl.backend.network;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.Topic;
import org.junit.jupiter.api.Test;

class NetworkRolePolicyTest {
    private static final MinecraftCredential MEMBER_KEY = new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "k1", "lobby");

    @Test
    void aMemberKeyIsAdmittedOnlyAsAMember() {
        assertThat(NetworkRolePolicy.admittedRole(MEMBER_KEY, ServerRole.SERVER_ROLE_MEMBER)).contains(ServerRole.SERVER_ROLE_MEMBER);
        assertThat(NetworkRolePolicy.admittedRole(MEMBER_KEY, ServerRole.SERVER_ROLE_CORE)).isEmpty();
        assertThat(NetworkRolePolicy.admittedRole(MEMBER_KEY, ServerRole.SERVER_ROLE_UNSPECIFIED)).isEmpty();
        assertThat(NetworkRolePolicy.admittedRole(MinecraftCredential.instanceKey(), ServerRole.SERVER_ROLE_UNSPECIFIED))
            .contains(ServerRole.SERVER_ROLE_CORE);
    }

    @Test
    void theStartupRoleNameParsesIntoTheSameRoles() {
        assertThat(NetworkRolePolicy.requestedRole(" member ")).isEqualTo(ServerRole.SERVER_ROLE_MEMBER);
        assertThat(NetworkRolePolicy.requestedRole("core")).isEqualTo(ServerRole.SERVER_ROLE_CORE);
        assertThat(NetworkRolePolicy.requestedRole((String) null)).isEqualTo(ServerRole.SERVER_ROLE_CORE);
    }

    @Test
    void aCoreIsGrantedOnlyTopicsTheBackendPublishesToIt() {
        assertThat(NetworkRolePolicy.topicsFor(ServerRole.SERVER_ROLE_CORE))
            .contains(Topic.TOPIC_MINECRAFT_STAFF_NOTIFICATIONS)
            .doesNotContain(Topic.TOPIC_MINECRAFT_PRESENCE, Topic.TOPIC_MINECRAFT_STAT_WIPES, Topic.TOPIC_NETWORK_MODERATION);
        assertThat(NetworkRolePolicy.topicsFor(ServerRole.SERVER_ROLE_MEMBER))
            .containsExactly(Topic.TOPIC_NETWORK_MODERATION, Topic.TOPIC_NETWORK_REPLAY_CAPTURE, Topic.TOPIC_MINECRAFT_FEATURE_SETTINGS);
    }
}
