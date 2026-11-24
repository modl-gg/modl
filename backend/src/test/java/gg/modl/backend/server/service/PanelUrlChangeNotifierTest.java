package gg.modl.backend.server.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.PublicUrlChangedEvent;
import gg.modl.backend.realtime.dispatch.RealtimeEventDispatcher;
import gg.modl.backend.realtime.dispatch.RealtimeOutboundEvent;
import gg.modl.backend.realtime.dispatch.RealtimeTopicPayloadValidator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.SyncRequestReason;
import gg.modl.proto.modl.v1.Topic;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PanelUrlChangeNotifierTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private final RealtimeEventDispatcher dispatcher = mock(RealtimeEventDispatcher.class);
    private final InstanceContext instanceContext = mock(InstanceContext.class);
    private PanelUrlChangeNotifier notifier;

    @BeforeEach
    void setUp() {
        Server instance = TestServers.server("Demo", "admin@example.com");
        instance.setId("server-1");
        when(instanceContext.instance()).thenReturn(instance);
        notifier = new PanelUrlChangeNotifier(dispatcher, instanceContext, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void panelUrlChangesAskMinecraftServersToResync() {
        notifier.onPublicUrlChanged(new PublicUrlChangedEvent("https://panel.example.com"));

        ArgumentCaptor<RealtimeOutboundEvent> captor = ArgumentCaptor.forClass(RealtimeOutboundEvent.class);
        verify(dispatcher).publish(captor.capture());
        RealtimeOutboundEvent event = captor.getValue();
        assertEquals("server-1", event.serverId());
        assertEquals(Topic.TOPIC_MINECRAFT_PERMISSIONS, event.topic());
        assertEquals(RealtimeEnvelope.PayloadCase.SYNC_REQUESTED, event.envelope().getPayloadCase());
        assertEquals(SyncRequestReason.SYNC_REQUEST_REASON_PANEL_URL_CHANGED, event.envelope().getSyncRequested().getReason());
        assertEquals("server-1::syncRequested::" + NOW, event.envelope().getEventId());
        assertTrue(new RealtimeTopicPayloadValidator().isValid(event));
    }

    @Test
    void dispatchFailuresDoNotBreakThePanelUrlChange() {
        doThrow(new IllegalStateException("dispatch failed")).when(dispatcher).publish(any(RealtimeOutboundEvent.class));

        assertDoesNotThrow(() -> notifier.onPublicUrlChanged(new PublicUrlChangedEvent("https://panel.example.com")));
    }
}
