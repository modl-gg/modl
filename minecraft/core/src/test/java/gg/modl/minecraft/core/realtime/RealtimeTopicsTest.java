package gg.modl.minecraft.core.realtime;

import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.proto.modl.v1.Topic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeTopicsTest {
    @Test
    void grantsPermissionAndPunishmentTypeTopics() {
        StartupResponse response = response(
                Topic.TOPIC_MINECRAFT_PERMISSIONS.name(),
                Topic.TOPIC_MINECRAFT_PUNISHMENT_TYPES.name()
        );

        assertFalse(RealtimeTopics.syncTopicsGrantedBy(response).isEmpty());
    }

    @Test
    void doesNotGrantPresenceEvenWhenStartupProvidesServerInstanceId() {
        StartupResponse response = responseWithInstance("instance-1", Topic.TOPIC_MINECRAFT_PRESENCE.name());

        assertTrue(RealtimeTopics.syncTopicsGrantedBy(response).isEmpty());
    }

    @Test
    void doesNotGrantPresenceWithoutServerInstanceId() {
        StartupResponse response = response(Topic.TOPIC_MINECRAFT_PRESENCE.name());

        assertTrue(RealtimeTopics.syncTopicsGrantedBy(response).isEmpty());
    }

    @Test
    void grantsNothingWhenTheBackendDisablesRealtime() {
        StartupResponse response = new StartupResponse(null, null, null, false, 1,
                Collections.singletonList(Topic.TOPIC_MINECRAFT_PERMISSIONS.name()));

        assertTrue(RealtimeTopics.syncTopicsGrantedBy(response).isEmpty());
        assertTrue(RealtimeTopics.syncTopicsGrantedBy(null).isEmpty());
    }

    @Test
    void keepsTheStartupOrderAndSkipsUnknownNames() {
        StartupResponse response = response(
                Topic.TOPIC_MINECRAFT_STAT_WIPES.name(), "TOPIC_UNKNOWN", " ",
                Topic.TOPIC_MINECRAFT_PERMISSIONS.name());

        assertEquals(Arrays.asList(Topic.TOPIC_MINECRAFT_STAT_WIPES, Topic.TOPIC_MINECRAFT_PERMISSIONS),
                new ArrayList<>(RealtimeTopics.syncTopicsGrantedBy(response)));
    }

    private StartupResponse response(String... topics) {
        return responseWithInstance(null, topics);
    }

    private StartupResponse responseWithInstance(String serverInstanceId, String... topics) {
        return new StartupResponse(null, null, serverInstanceId, true, 1,
                topics == null ? Collections.emptyList() : Arrays.asList(topics));
    }
}
