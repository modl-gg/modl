package gg.modl.backend.realtime.lifecycle;

import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.transport.RealtimeCloseStatuses;
import gg.modl.backend.realtime.transport.RealtimeCodec;
import gg.modl.backend.realtime.transport.RealtimeSessionOperations;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
public class RealtimeDeployDrainManager {
    static final CloseStatus DEPLOY_DRAIN =
        RealtimeCloseStatuses.of(RealtimeCloseCode.REALTIME_CLOSE_CODE_DEPLOY_DRAIN, "Realtime deploy drain");

    private final RealtimeConnectionRegistry connectionRegistry;
    private final RealtimeConnectionCleanup connectionCleanup;
    private final RealtimeCodec codec;
    private final RealtimeSessionOperations sessionOperations;

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        drainForDeploy();
    }

    void drainForDeploy() {
        for (RealtimeConnectionRegistry.RealtimeConnectionSnapshot snapshot : connectionRegistry.snapshot()) {
            WebSocketSession session = snapshot.session();
            RealtimeConnectionState state = snapshot.state();
            if (!session.isOpen()) {
                connectionCleanup.unregister(session, CloseStatus.NO_CLOSE_FRAME);
                continue;
            }

            closeWithAdvice(session, state);
        }
    }

    private void closeWithAdvice(WebSocketSession session, RealtimeConnectionState state) {
        sessionOperations.sendThenClose(session, state, codec.deployDrainAdvice(), DEPLOY_DRAIN, "deploy_drain");
    }
}
