package gg.modl.backend.realtime.lifecycle;

import gg.modl.backend.realtime.state.RealtimeConnectionState;

public record RealtimeConnectionClosedEvent(RealtimeConnectionState state) {
}
