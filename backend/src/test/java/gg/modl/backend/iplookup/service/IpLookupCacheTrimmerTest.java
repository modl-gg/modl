package gg.modl.backend.iplookup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.iplookup.repository.IpLookupRepository;
import gg.modl.backend.player.config.IpHistoryPolicy;
import gg.modl.backend.retention.RetentionBatches;
import gg.modl.backend.support.TestServers;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IpLookupCacheTrimmerTest {
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private final IpLookupRepository repository = mock(IpLookupRepository.class);
    private final IpLookupCacheTrimmer trimmer = new IpLookupCacheTrimmer(repository, IpHistoryPolicy.ofDays(365));

    @Test
    void theCacheHoldsAHundredThousandLookups() {
        assertThat(IpLookupCacheTrimmer.MAX_ENTRIES).isEqualTo(100_000);
    }

    @Test
    void lookupsNeverOutliveThePlayerIpHistory() {
        Instant cutoff = NOW.minus(Duration.ofDays(365));
        when(repository.deleteLookedUpBefore(cutoff, 500)).thenReturn(500, 12);
        when(repository.count()).thenReturn(10L);

        assertThat(trimmer.purge(TestServers.server("s", "o@example.com"), NOW)).isEqualTo(512);

        verify(repository, never()).deleteOldest(anyInt());
    }

    @Test
    void aCacheUnderTheCapIsLeftAlone() {
        when(repository.count()).thenReturn(IpLookupCacheTrimmer.MAX_ENTRIES);

        assertThat(trimmer.purge(TestServers.server("s", "o@example.com"), NOW)).isZero();

        verify(repository, never()).deleteOldest(anyInt());
    }

    @Test
    void theExcessIsDroppedOldestFirstInBatchesOfAtMostFiveHundred() {
        when(repository.count()).thenReturn(IpLookupCacheTrimmer.MAX_ENTRIES + 700);
        when(repository.deleteOldest(500)).thenReturn(500);
        when(repository.deleteOldest(200)).thenReturn(200);

        assertThat(trimmer.purge(TestServers.server("s", "o@example.com"), NOW)).isEqualTo(700);

        verify(repository).deleteOldest(500);
        verify(repository).deleteOldest(eq(200));
    }

    @Test
    void theCapPassStopsAtTheRetentionBatchBudget() {
        when(repository.count()).thenReturn(IpLookupCacheTrimmer.MAX_ENTRIES * 10);
        when(repository.deleteOldest(500)).thenReturn(500);

        assertThat(trimmer.purge(TestServers.server("s", "o@example.com"), NOW)).isEqualTo(100_000);

        verify(repository, times(RetentionBatches.DEFAULT_MAX_BATCHES_PER_RUN)).deleteOldest(500);
    }
}
