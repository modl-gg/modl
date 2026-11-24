package gg.modl.backend.retention.service;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DataRetentionSweeperTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private final Server server = TestServers.server("server", "admin@example.com");
    private final List<Instant> seenNow = new ArrayList<>();

    @Test
    void reportsWhatEachTaskRemovedAndKeepsGoingAfterAFailure() {
        DataRetentionSweeper sweeper = sweeper(
            task("chat logs", 120),
            failing("command logs"),
            task("audit log entries", 0),
            task("replays", 3));

        Map<String, Long> removed = sweeper.sweep(server);

        assertThat(removed).containsExactly(Map.entry("chat logs", 120L), Map.entry("replays", 3L));
        assertThat(seenNow).hasSize(3).containsOnly(NOW);
    }

    private DataRetentionSweeper sweeper(RetentionTask... tasks) {
        return new DataRetentionSweeper(List.of(tasks), Mockito.mock(InstanceContext.class),
            Mockito.mock(ScheduledDatabaseJobs.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RetentionTask task(String subject, long removed) {
        return new RetentionTask() {
            @Override
            public String subject() {
                return subject;
            }

            @Override
            public long purge(Server target, Instant now) {
                seenNow.add(now);
                return removed;
            }
        };
    }

    private static RetentionTask failing(String subject) {
        return new RetentionTask() {
            @Override
            public String subject() {
                return subject;
            }

            @Override
            public long purge(Server target, Instant now) {
                throw new IllegalStateException("database unavailable");
            }
        };
    }
}
