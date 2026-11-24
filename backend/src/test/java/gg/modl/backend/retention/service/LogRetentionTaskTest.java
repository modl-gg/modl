package gg.modl.backend.retention.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.retention.repository.AgeRetainedRecords;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.DataRetentionSettings;
import gg.modl.backend.settings.service.DataRetentionSettingsService;
import gg.modl.backend.support.TestServers;
import java.time.Duration;
import java.time.Instant;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogRetentionTaskTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private final Server server = TestServers.server("server", "admin@example.com");
    private AgeRetainedRecords records;
    private DataRetentionSettingsService settingsService;

    @BeforeEach
    void setUp() {
        records = mock(AgeRetainedRecords.class);
        settingsService = mock(DataRetentionSettingsService.class);
        when(settingsService.getDataRetentionSettings(server)).thenReturn(new DataRetentionSettings(30, 365));
    }

    @Test
    void deletesRecordsOlderThanTheRetentionPeriodUntilABatchComesBackShort() {
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        when(records.deleteOldestBatch(cutoff, 2)).thenReturn(2L, 2L, 1L);

        long removed = task(DataRetentionSettings::getChatLogDays, 10).purge(server, NOW);

        assertThat(removed).isEqualTo(5);
        verify(records, times(3)).deleteOldestBatch(cutoff, 2);
    }

    @Test
    void stopsAtTheBatchLimitAndLeavesTheRestForTheNextRun() {
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        when(records.deleteOldestBatch(cutoff, 2)).thenReturn(2L);

        long removed = task(DataRetentionSettings::getChatLogDays, 3).purge(server, NOW);

        assertThat(removed).isEqualTo(6);
        verify(records, times(3)).deleteOldestBatch(cutoff, 2);
    }

    @Test
    void auditLogsFollowTheirOwnRetentionPeriod() {
        Instant cutoff = NOW.minus(Duration.ofDays(365));
        when(records.deleteOldestBatch(cutoff, 2)).thenReturn(0L);

        assertThat(task(DataRetentionSettings::getAuditLogDays, 10).purge(server, NOW)).isZero();
        verify(records).deleteOldestBatch(cutoff, 2);
    }

    private LogRetentionTask task(ToIntFunction<DataRetentionSettings> retentionDays, int maxBatches) {
        return new LogRetentionTask("records", records, retentionDays, settingsService, 2, maxBatches);
    }
}
