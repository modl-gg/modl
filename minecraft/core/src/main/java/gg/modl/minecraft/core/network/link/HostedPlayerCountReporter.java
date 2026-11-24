package gg.modl.minecraft.core.network.link;

import gg.modl.proto.modl.v1.HostedPlayersEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;

import java.util.function.IntSupplier;
import java.util.function.Predicate;

final class HostedPlayerCountReporter {
    private static final int NOT_REPORTED = -1;

    private final IntSupplier onlineCount;
    private final Predicate<RealtimeEnvelope> sender;
    private boolean connected;
    private int reported = NOT_REPORTED;
    private long sequence;

    HostedPlayerCountReporter(IntSupplier onlineCount, Predicate<RealtimeEnvelope> sender) {
        this.onlineCount = onlineCount;
        this.sender = sender;
    }

    synchronized void connected() {
        connected = true;
        reported = NOT_REPORTED;
        flush();
    }

    synchronized void disconnected() {
        connected = false;
    }

    synchronized void flush() {
        if (!connected) return;
        int count = onlineCount.getAsInt();
        if (count == reported) return;
        HostedPlayersEvent event = HostedPlayersEvent.newBuilder()
                .setSequence(++sequence)
                .setOnlineCount(count)
                .build();
        if (sender.test(RealtimeEnvelope.newBuilder().setHostedPlayers(event).build())) reported = count;
    }
}
