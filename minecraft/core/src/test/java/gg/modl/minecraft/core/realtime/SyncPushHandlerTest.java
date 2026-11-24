package gg.modl.minecraft.core.realtime;

import gg.modl.minecraft.core.service.ChatCommandLogService;
import gg.modl.minecraft.core.service.sync.SyncService;
import gg.modl.minecraft.core.service.sync.SyncServiceContext;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.proto.modl.v1.Heartbeat;
import gg.modl.proto.modl.v1.PermissionInvalidatedEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.SyncRequestedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SyncPushHandlerTest {
    private final RecordingSync sync = new RecordingSync();
    private final SyncPushHandler handler = new SyncPushHandler(sync, new RecordingPluginLogger(), false);

    @Test
    void aSyncRequestRunsAFullSyncSoANewPanelUrlApplies() {
        handler.domainAction(RealtimeEnvelope.newBuilder()
                .setSyncRequested(SyncRequestedEvent.getDefaultInstance()).build()).run();

        assertEquals(Collections.singletonList("sync"), sync.calls);
    }

    @Test
    void aPermissionInvalidationOnlyRefreshesPermissions() {
        handler.domainAction(RealtimeEnvelope.newBuilder()
                .setPermissionInvalidated(PermissionInvalidatedEvent.getDefaultInstance()).build()).run();

        assertEquals(Collections.singletonList("permissions"), sync.calls);
    }

    @Test
    void transportPayloadsAreNotDomainEvents() {
        assertNull(handler.domainAction(RealtimeEnvelope.newBuilder().setHeartbeat(Heartbeat.getDefaultInstance()).build()));
    }

    @Test
    void connectionChangesDriveTheBaselineSync() {
        handler.onConnected(true);
        handler.onConnected(false);
        handler.onResyncAdvised();
        handler.onDisconnected();

        assertEquals(Arrays.asList("connected", "sync", "connected", "sync", "sync", "disconnected"), sync.calls);
    }

    private static final class RecordingSync extends SyncService {
        private final List<String> calls = new ArrayList<>();

        private RecordingSync() {
            super(SyncServiceContext.builder().logger(new RecordingPluginLogger())
                    .chatCommandLogService(new ChatCommandLogService()).build());
        }

        @Override
        public void runBaselineFetch(String reason) {
            calls.add("sync");
        }

        @Override
        public void refreshStaffPermissionsNow() {
            calls.add("permissions");
        }

        @Override
        public void setRealtimeConnected(boolean connected) {
            calls.add(connected ? "connected" : "disconnected");
        }
    }
}
