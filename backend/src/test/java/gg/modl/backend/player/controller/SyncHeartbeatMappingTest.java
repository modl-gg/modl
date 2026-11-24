package gg.modl.backend.player.controller;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.player.controller.MinecraftSyncController.OnlinePlayer;
import gg.modl.backend.player.controller.MinecraftSyncV2Controller.V2SyncRequest;
import gg.modl.backend.server.connection.ServerHeartbeat;
import gg.modl.proto.modl.v1.SyncOnlinePlayer;
import gg.modl.proto.modl.v1.SyncRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncHeartbeatMappingTest {

    @Test
    void aV2SyncReportsNameAndPlayers() {
        V2SyncRequest request = new V2SyncRequest("2026-11-21T00:00:00Z",
            List.of(new OnlinePlayer("0f8fad5b-d9cb-469f-a165-70867728950e", "Steve", "203.0.113.7")), "Network", List.of(), List.of(),
            "instance-1");

        assertThat(SyncRequestMapper.toHeartbeat(request)).isEqualTo(new ServerHeartbeat("instance-1", "Network", 1));
    }

    @Test
    void aV2SyncWithoutOptionalListsStillReports() {
        V2SyncRequest request = new V2SyncRequest(null, null, null, null, null, "instance-1");

        assertThat(SyncRequestMapper.toHeartbeat(request)).isEqualTo(new ServerHeartbeat("instance-1", "", 0));
    }

    @Test
    void aV3SyncReportsTheSameFields() {
        SyncRequest request = SyncRequest.newBuilder()
            .setServerInstanceId("instance-1")
            .setServerName("Network")
            .addOnlinePlayers(SyncOnlinePlayer.newBuilder().setUuid("u").setUsername("Steve"))
            .build();

        assertThat(MinecraftSyncProtoMapper.toHeartbeat(request)).isEqualTo(new ServerHeartbeat("instance-1", "Network", 1));
    }
}
