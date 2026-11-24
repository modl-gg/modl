package gg.modl.minecraft.core.imports.sql;

import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.UnreadablePlayerException;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class BatchedPlayers implements PlayerRecords {
    public static final int BATCH_SIZE = 500;

    private final Keys keys;
    private final BatchLoader loader;
    private final Probe probe;
    private final Deque<Outcome> ready = new ArrayDeque<>();
    private boolean exhausted;

    public BatchedPlayers(Keys keys, BatchLoader loader, Probe probe) {
        this.keys = keys;
        this.loader = loader;
        this.probe = probe;
    }

    @Override
    public @Nullable SourcePlayer next() throws SourceException, UnreadablePlayerException {
        while (ready.isEmpty()) {
            if (exhausted) return null;
            fill();
        }
        return ready.removeFirst().player();
    }

    private void fill() throws SourceException {
        List<String> batch = nextKeys();
        if (batch.isEmpty()) {
            exhausted = true;
            return;
        }
        try {
            queue(batch, loader.load(batch));
        } catch (SQLException | RuntimeException failure) {
            requireDatabase(failure);
            for (String uuid : batch) loadAlone(uuid);
        }
    }

    private List<String> nextKeys() throws SourceException {
        try {
            return keys.next(BATCH_SIZE);
        } catch (SQLException | RuntimeException failure) {
            throw SourceException.failed(failure);
        }
    }

    private void loadAlone(String uuid) throws SourceException {
        try {
            queue(Collections.singletonList(uuid), loader.load(Collections.singletonList(uuid)));
        } catch (SQLException | RuntimeException failure) {
            requireDatabase(failure);
            ready.addLast(Outcome.failed(new UnreadablePlayerException(uuid, failure)));
        }
    }

    private void queue(List<String> batch, Map<String, SourcePlayer> loaded) {
        for (String uuid : batch) {
            SourcePlayer player = loaded.get(uuid);
            if (player != null) ready.addLast(Outcome.read(player));
        }
    }

    private void requireDatabase(Exception failure) throws SourceException {
        try {
            probe.check();
        } catch (SQLException | RuntimeException lost) {
            lost.addSuppressed(failure);
            throw SourceException.failed(lost);
        }
    }

    @FunctionalInterface
    public interface Keys {
        List<String> next(int limit) throws SQLException;

        static Keys of(Iterable<String> uuids) {
            Iterator<String> remaining = uuids.iterator();
            return limit -> {
                List<String> page = new ArrayList<>(limit);
                while (remaining.hasNext() && page.size() < limit) page.add(remaining.next());
                return page;
            };
        }
    }

    @FunctionalInterface
    public interface BatchLoader {
        Map<String, SourcePlayer> load(List<String> uuids) throws SQLException;
    }

    @FunctionalInterface
    public interface Probe {
        void check() throws SQLException;
    }

    private static final class Outcome {
        private final @Nullable SourcePlayer player;
        private final @Nullable UnreadablePlayerException failure;

        private Outcome(@Nullable SourcePlayer player, @Nullable UnreadablePlayerException failure) {
            this.player = player;
            this.failure = failure;
        }

        static Outcome read(SourcePlayer player) {
            return new Outcome(player, null);
        }

        static Outcome failed(UnreadablePlayerException failure) {
            return new Outcome(null, failure);
        }

        SourcePlayer player() throws UnreadablePlayerException {
            if (failure != null) throw failure;
            if (player == null) throw new IllegalStateException("A batched player has neither a record nor a failure");
            return player;
        }
    }
}
