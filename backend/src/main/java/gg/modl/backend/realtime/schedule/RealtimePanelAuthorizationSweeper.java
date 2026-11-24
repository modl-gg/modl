package gg.modl.backend.realtime.schedule;

import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.realtime.auth.RealtimeClientKind;
import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.auth.RealtimeTopicAuthorizer;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry.RealtimeConnectionSnapshot;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.transport.RealtimeCloseStatuses;
import gg.modl.backend.realtime.transport.RealtimeSessionOperations;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import gg.modl.proto.modl.v1.Topic;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

@Component
@RequiredArgsConstructor
public class RealtimePanelAuthorizationSweeper {
    private static final String REVOCATION_PHASE = "resweep";
    private static final CloseStatus SESSION_ENDED =
        RealtimeCloseStatuses.of(RealtimeCloseCode.REALTIME_CLOSE_CODE_SESSION_ENDED, "Panel session ended");
    private static final String SESSION_ENDED_REASON = "panel_session_ended";

    private final RealtimeProperties properties;
    private final RealtimeConnectionRegistry connectionRegistry;
    private final RealtimeTopicAuthorizer topicAuthorizer;
    private final RealtimeMetrics metrics;
    private final SessionService sessionService;
    private final RealtimeSessionOperations sessionOperations;

    @Scheduled(fixedDelayString = "${modl.realtime.ws.panel-authorization-sweep-interval-ms:30000}")
    public void enforcePanelAuthorization() {
        if (!properties.isEnabled()) {
            return;
        }

        List<RealtimeConnectionSnapshot> panelConnections = connectionRegistry.snapshot().stream()
            .filter(snapshot -> snapshot.state().isAuthenticated()
                && snapshot.state().getClientKind() == RealtimeClientKind.PANEL)
            .toList();
        Set<String> liveSessionIds = liveSessionIds(panelConnections);
        for (RealtimeConnectionSnapshot snapshot : panelConnections) {
            RealtimeConnectionState state = snapshot.state();
            if (!liveSessionIds.contains(state.getPrincipal().sessionId())) {
                sessionOperations.requestClose(snapshot.session(), state, SESSION_ENDED, SESSION_ENDED_REASON);
                continue;
            }
            revokeStaleSubscriptions(state);
        }
    }

    private Set<String> liveSessionIds(List<RealtimeConnectionSnapshot> panelConnections) {
        Map<String, List<RealtimePrincipal>> principalsByServer = panelConnections.stream()
            .map(snapshot -> snapshot.state().getPrincipal())
            .filter(principal -> principal.sessionId() != null)
            .collect(Collectors.groupingBy(RealtimePrincipal::serverId));
        Set<String> live = new HashSet<>();
        principalsByServer.values().forEach(principals -> live.addAll(sessionService.activeSessionIds(
            principals.getFirst().server(),
            principals.stream().map(RealtimePrincipal::sessionId).collect(Collectors.toSet()))));
        return live;
    }

    private void revokeStaleSubscriptions(RealtimeConnectionState state) {
        RealtimePrincipal principal = state.getPrincipal();
        for (Topic topic : state.getSubscriptions()) {
            if (!topicAuthorizer.canSubscribe(principal, topic)) {
                state.unsubscribe(topic);
                metrics.recordTopicAuthorizationFailure(state, topic, REVOCATION_PHASE);
            }
        }
    }
}
