package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.config.IpHistoryPolicy;
import gg.modl.backend.player.repository.IpHistoryKey;
import gg.modl.backend.player.repository.IpPruneBatch;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.retention.RetentionBatches;
import gg.modl.backend.support.TestServers;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IpHistoryRetentionTaskTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final int BATCH = RetentionBatches.DEFAULT_BATCH_SIZE;

    private final PlayerRepository players = mock(PlayerRepository.class);

    @Test
    void addressesUnseenForTheConfiguredDaysAreRemovedInBatches() {
        Instant cutoff = NOW.minus(Duration.ofDays(365));
        IpHistoryKey cursor = new IpHistoryKey("player-500", "198.51.100.9");
        when(players.pruneIpsUnseenSince(cutoff, NOW, null, BATCH)).thenReturn(new IpPruneBatch(BATCH - 3, cursor));
        when(players.pruneIpsUnseenSince(cutoff, NOW, cursor, BATCH)).thenReturn(new IpPruneBatch(12L, null));

        assertEquals(BATCH - 3 + 12L, purge());

        verify(players).pruneIpsUnseenSince(cutoff, NOW, cursor, BATCH);
        verify(players, times(2)).pruneIpsUnseenSince(eq(cutoff), eq(NOW), any(), eq(BATCH));
    }

    @Test
    void zeroDaysIsRefusedBecauseItWouldEraseTheWholeHistory() {
        assertThrows(IllegalArgumentException.class, () -> IpHistoryPolicy.ofDays(0));
    }

    private long purge() {
        return new IpHistoryRetentionTask(players, IpHistoryPolicy.ofDays(365))
            .purge(TestServers.server("server", "admin@example.com"), NOW);
    }
}
