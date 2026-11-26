package gg.modl.backend.tunnel.cloudflared;

import gg.modl.backend.tunnel.TunnelPhase;
import gg.modl.backend.tunnel.TunnelState;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

final class ConnectionWatch {
    private final Duration notConnectedAfter;
    private TunnelPhase phase = TunnelPhase.CONNECTING;
    private @Nullable String problem;
    private @Nullable Instant notConnectedSince;
    private boolean connectedAnnounced;
    private boolean failureAnnounced;
    private boolean unavailableAnnounced;
    private boolean connectedThisRun;

    ConnectionWatch(Duration notConnectedAfter) {
        this.notConnectedAfter = notConnectedAfter;
    }

    synchronized TunnelState state() {
        return new TunnelState(phase, phase == TunnelPhase.UNAVAILABLE ? problem : null);
    }

    synchronized void launched(Instant now) {
        connectedThisRun = false;
        if (phase == TunnelPhase.UNAVAILABLE) {
            phase = TunnelPhase.CONNECTING;
            failureAnnounced = false;
            notConnectedSince = now;
        }
        if (notConnectedSince == null) {
            notConnectedSince = now;
        }
    }

    synchronized Announcement connected() {
        connectedThisRun = true;
        if (phase == TunnelPhase.CONNECTED) {
            return Announcement.NONE;
        }
        phase = TunnelPhase.CONNECTED;
        notConnectedSince = null;
        unavailableAnnounced = false;
        boolean announce = !connectedAnnounced || failureAnnounced;
        connectedAnnounced = true;
        failureAnnounced = false;
        return announce ? Announcement.CONNECTED : Announcement.NONE;
    }

    synchronized void disconnected(Instant now) {
        if (phase == TunnelPhase.CONNECTED) {
            phase = TunnelPhase.CONNECTING;
            notConnectedSince = now;
        }
    }

    synchronized boolean exited(Instant now) {
        disconnected(now);
        return connectedThisRun;
    }

    synchronized Announcement unavailable(String reason) {
        phase = TunnelPhase.UNAVAILABLE;
        problem = reason;
        failureAnnounced = true;
        if (unavailableAnnounced) {
            return Announcement.NONE;
        }
        unavailableAnnounced = true;
        return Announcement.UNAVAILABLE;
    }

    synchronized Announcement check(Instant now) {
        Instant since = notConnectedSince;
        if (phase != TunnelPhase.CONNECTING || failureAnnounced || since == null
            || Duration.between(since, now).compareTo(notConnectedAfter) < 0) {
            return Announcement.NONE;
        }
        failureAnnounced = true;
        phase = TunnelPhase.NOT_CONNECTED;
        return Announcement.NOT_CONNECTED;
    }

    enum Announcement {
        NONE,
        CONNECTED,
        NOT_CONNECTED,
        UNAVAILABLE
    }
}
