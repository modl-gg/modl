package gg.modl.minecraft.core.realtime;

import gg.modl.minecraft.core.boot.VersionMismatchFailure;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.support.TestConnections;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.Topic;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeConnectionNetworkTest {
    @Test
    void rateLimitAndVersionMismatchClosesReconnectInsteadOfStopping() {
        int rateLimited = RealtimeCloseCode.REALTIME_CLOSE_CODE_RATE_LIMITED.getNumber();
        int mismatch = RealtimeCloseCode.REALTIME_CLOSE_CODE_VERSION_MISMATCH.getNumber();

        assertTrue(RealtimeConnection.isRateLimitedClose(rateLimited));
        assertTrue(RealtimeConnection.isVersionMismatchClose(mismatch));
        assertFalse(RealtimeConnection.terminationForClose(rateLimited).isPresent());
        assertFalse(RealtimeConnection.terminationForClose(mismatch).isPresent());
        assertTrue(RealtimeConnection.RATE_LIMITED_RECONNECT_DELAY_MS <= 60_000);
    }

    @Test
    void aVersionMismatchCloseNamesTheServerVersion() {
        RealtimeConnection connection = new RealtimeConnection(TestConnections.to("http://127.0.0.1:1", "key"), 1,
                Collections.singleton(Topic.TOPIC_NETWORK_MODERATION), () -> "lobby", null, ServerRole.SERVER_ROLE_MEMBER,
                new RecordingPluginLogger(), false);

        VersionMismatchFailure failure = connection.versionMismatch("9.0.1");

        assertEquals("9.0.1", failure.serverVersion());
        assertEquals("", connection.versionMismatch("Version mismatch: modl server 9.0.1").serverVersion());
        assertEquals("", connection.versionMismatch("").serverVersion());
    }
}
