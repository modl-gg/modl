package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.proto.modl.v1.HostedPlayersEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class HostedPlayersReporter {
    static final int CHUNK_SIZE = 1000;
    static final long FLUSH_INTERVAL_MILLIS = 250;

    private final Supplier<Collection<UUID>> hostedPlayers;
    private final Predicate<RealtimeEnvelope> sender;
    private final PluginLogger logger;
    private Set<UUID> reported = new HashSet<>();
    private boolean connected;
    private long sequence;

    HostedPlayersReporter(Supplier<Collection<UUID>> hostedPlayers, Predicate<RealtimeEnvelope> sender,
                          PluginLogger logger) {
        this.logger = logger;
        this.hostedPlayers = hostedPlayers;
        this.sender = sender;
    }

    void connected() {
        reportSafely(this::reportEverything);
    }

    void flush() {
        reportSafely(this::reportChanges);
    }

    private void reportSafely(Runnable report) {
        try {
            report.run();
        } catch (RuntimeException failure) {
            logger.debug("[network] Could not report the players on this server: " + failure);
        }
    }

    private synchronized void reportEverything() {
        connected = true;
        reported = new HashSet<>();
        Set<UUID> current = new HashSet<>(hostedPlayers.get());
        List<String> all = names(current);
        boolean first = true;
        for (List<String> chunk : chunks(all)) {
            if (!send(first, chunk, new ArrayList<String>(), current.size())) return;
            first = false;
        }
        if (first && !send(true, new ArrayList<String>(), new ArrayList<String>(), 0)) return;
        reported = current;
    }

    synchronized void disconnected() {
        connected = false;
    }

    private synchronized void reportChanges() {
        if (!connected) return;
        Set<UUID> current = new HashSet<>(hostedPlayers.get());
        if (current.equals(reported)) return;
        List<String> joined = new ArrayList<>();
        for (UUID uuid : current) if (!reported.contains(uuid)) joined.add(uuid.toString());
        List<String> left = new ArrayList<>();
        for (UUID uuid : reported) if (!current.contains(uuid)) left.add(uuid.toString());
        List<List<String>> joinedChunks = chunks(joined);
        List<List<String>> leftChunks = chunks(left);
        int rounds = Math.max(joinedChunks.size(), leftChunks.size());
        for (int index = 0; index < rounds; index++) {
            List<String> joinedChunk = index < joinedChunks.size() ? joinedChunks.get(index) : new ArrayList<String>();
            List<String> leftChunk = index < leftChunks.size() ? leftChunks.get(index) : new ArrayList<String>();
            if (!send(false, joinedChunk, leftChunk, current.size())) return;
        }
        reported = current;
    }

    private boolean send(boolean replace, List<String> joined, List<String> left, int onlineCount) {
        HostedPlayersEvent event = HostedPlayersEvent.newBuilder()
                .setReplace(replace)
                .addAllJoinedUuids(joined)
                .addAllLeftUuids(left)
                .setSequence(++sequence)
                .setOnlineCount(onlineCount)
                .build();
        return sender.test(RealtimeEnvelope.newBuilder().setHostedPlayers(event).build());
    }

    private static List<String> names(Set<UUID> uuids) {
        List<String> names = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) names.add(uuid.toString());
        return names;
    }

    private static List<List<String>> chunks(List<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += CHUNK_SIZE) {
            chunks.add(new ArrayList<>(values.subList(start, Math.min(values.size(), start + CHUNK_SIZE))));
        }
        return chunks;
    }
}
