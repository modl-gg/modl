package gg.modl.minecraft.core.network;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationPublisherTest {
    private final ModerationSessionService moderation = new ModerationSessionService(() -> 1L, "epoch");
    private final ManualExecutor executor = new ManualExecutor();
    private final ModerationPublisher publisher =
            new ModerationPublisher(moderation::snapshot, executor, () -> { }, new RecordingPluginLogger());
    private final RecordingSink sink = new RecordingSink();

    ModerationPublisherTest() {
        moderation.addChangeListener(publisher::changed);
    }

    @Test
    void aNewSinkReceivesTheCurrentStateAtOnce() {
        moderation.freeze(UUID.randomUUID(), UUID.randomUUID());
        executor.runAll();

        publisher.addSink(sink);
        executor.runAll();

        assertEquals(1, sink.received.size());
        assertEquals(1, sink.received.get(0).getRevision());
    }

    @Test
    void burstsOfChangesCoalesceIntoOnePublishOfTheLatestState() {
        publisher.addSink(sink);
        executor.runAll();
        sink.received.clear();

        for (int i = 0; i < 5; i++) moderation.freeze(UUID.randomUUID(), UUID.randomUUID());

        assertEquals(1, executor.pending());
        executor.runAll();
        assertEquals(1, sink.received.size());
        assertEquals(5, sink.received.get(0).getRevision());
        assertEquals(5, sink.received.get(0).getState().getFrozen().size());
    }

    @Test
    void aChangeAfterAPublishSchedulesAnotherOne() {
        publisher.addSink(sink);
        moderation.freeze(UUID.randomUUID(), UUID.randomUUID());
        executor.runAll();
        sink.received.clear();

        moderation.freeze(UUID.randomUUID(), UUID.randomUUID());
        executor.runAll();

        assertEquals(1, sink.received.size());
        assertEquals(2, sink.received.get(0).getRevision());
    }

    @Test
    void theRepublishSendsTheSameRevisionAgain() {
        moderation.freeze(UUID.randomUUID(), UUID.randomUUID());
        executor.runAll();
        publisher.addSink(sink);
        executor.runAll();

        publisher.republish();
        publisher.republish();

        long last = sink.received.get(sink.received.size() - 1).getRevision();
        assertEquals(1, last);
        assertEquals(sink.received.get(0).getRevision(), last);
        assertEquals(3, sink.received.size());
    }

    @Test
    void theRepublishIsSkippedWithoutSinksAndARemovedSinkStopsReceiving() {
        publisher.republish();
        publisher.addSink(sink);
        executor.runAll();
        publisher.removeSink(sink);

        moderation.freeze(UUID.randomUUID(), UUID.randomUUID());
        executor.runAll();
        publisher.republish();

        assertEquals(1, sink.received.size());
    }

    @Test
    void aFailingSinkDoesNotStopTheOthers() {
        publisher.addSink(snapshot -> {
            throw new IllegalStateException("boom");
        });
        publisher.addSink(sink);
        executor.runAll();

        moderation.freeze(UUID.randomUUID(), UUID.randomUUID());
        executor.runAll();

        assertTrue(sink.received.size() >= 2);
    }

    private static final class RecordingSink implements ModerationStateSink {
        private final List<ModerationSnapshot> received = new ArrayList<>();

        @Override
        public void publish(ModerationSnapshot snapshot) {
            received.add(snapshot);
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        int pending() {
            return tasks.size();
        }

        void runAll() {
            while (!tasks.isEmpty()) tasks.poll().run();
        }
    }
}
