package gg.modl.backend.server.service;

import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.PublicUrlChangedEvent;
import gg.modl.backend.realtime.dispatch.RealtimeEventDispatcher;
import gg.modl.backend.realtime.dispatch.RealtimeOutboundEvent;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.SyncRequestReason;
import gg.modl.proto.modl.v1.SyncRequestedEvent;
import gg.modl.proto.modl.v1.Topic;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PanelUrlChangeNotifier {
    private static final String EVENT_KIND = "syncRequested";

    private final RealtimeEventDispatcher realtimeEventDispatcher;
    private final InstanceContext instanceContext;
    private final Clock clock;

    @EventListener
    public void onPublicUrlChanged(PublicUrlChangedEvent event) {
        Server server = instanceContext.instance();
        RealtimeEnvelope envelope = RealtimeEnvelope.newBuilder()
            .setEventId(server.getId() + "::" + EVENT_KIND + "::" + clock.instant())
            .setSyncRequested(SyncRequestedEvent.newBuilder()
                .setReason(SyncRequestReason.SYNC_REQUEST_REASON_PANEL_URL_CHANGED))
            .build();
        try {
            realtimeEventDispatcher.publish(new RealtimeOutboundEvent(server.getId(), Topic.TOPIC_MINECRAFT_PERMISSIONS, envelope));
        } catch (RuntimeException e) {
            log.warn("Failed to ask minecraft servers to resync after the panel URL changed", e);
        }
    }
}
