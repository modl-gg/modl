package gg.modl.minecraft.core.feature;

import gg.modl.minecraft.core.realtime.ConnectionListener;
import gg.modl.minecraft.core.realtime.RealtimeConnection;
import gg.modl.minecraft.core.realtime.RealtimeInbound;
import gg.modl.minecraft.core.realtime.TerminationReason;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.RealtimeEnvelope.PayloadCase;
import org.jspecify.annotations.Nullable;

public final class FeatureSettingsChannel implements RealtimeInbound {
    private final PluginLogger logger;
    private @Nullable FeatureSettingsTarget target;
    private @Nullable RealtimeConnection connection;
    private @Nullable FeatureSettings received;
    private long receivedVersion = Long.MIN_VALUE;
    private @Nullable FeatureStatus reported;

    public FeatureSettingsChannel(PluginLogger logger) {
        this.logger = logger;
    }

    public synchronized void attachTo(RealtimeConnection attached) {
        connection = attached;
        reported = null;
        receivedVersion = Long.MIN_VALUE;
        attached.register(PayloadCase.FEATURE_SETTINGS, this);
        attached.addListener(new Reconnects(attached));
    }

    public synchronized void bind(FeatureSettingsTarget bound) {
        target = bound;
        bound.reportStatusTo(this::statusChanged);
        FeatureSettings known = received;
        if (known != null) bound.applyFeatureSettings(known);
        report();
    }

    public synchronized void unbind() {
        target = null;
    }

    @Override
    public synchronized void receive(RealtimeEnvelope envelope) {
        FeatureSettings settings = FeatureSettings.fromProto(envelope.getFeatureSettings().getReplays());
        long version = envelope.getFeatureSettings().getVersion();
        if (version < receivedVersion) return;
        receivedVersion = version;
        received = settings;
        FeatureSettingsTarget current = target;
        if (current == null) return;
        try {
            current.applyFeatureSettings(settings);
        } catch (RuntimeException failure) {
            logger.warning("[replay] Could not apply the replay settings from the panel: " + failure.getMessage());
        }
    }

    public void statusChanged() {
        report();
    }

    private synchronized void report() {
        FeatureSettingsTarget current = target;
        RealtimeConnection open = connection;
        if (current == null || open == null || !open.isConnected()) return;
        try {
            FeatureStatus status = current.featureStatus();
            if (reported != null && reported.equals(status)) return;
            if (open.send(RealtimeEnvelope.newBuilder().setFeatureStatus(status.toProto()).build())) reported = status;
        } catch (RuntimeException failure) {
            logger.debug("[replay] Could not report the replay and anticheat status: " + failure);
        }
    }

    private synchronized void connected(RealtimeConnection which) {
        if (which != connection) return;
        reported = null;
        receivedVersion = Long.MIN_VALUE;
        report();
    }

    private final class Reconnects implements ConnectionListener {
        private final RealtimeConnection which;

        private Reconnects(RealtimeConnection which) {
            this.which = which;
        }

        @Override
        public void onConnected(boolean first) {
            connected(which);
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onResyncAdvised() {
        }

        @Override
        public void onReplaced() {
        }

        @Override
        public void onTerminated(TerminationReason reason) {
        }
    }
}
