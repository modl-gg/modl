package gg.modl.backend.serverkey.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyRevokedEvent;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.serverkey.repository.ServerKeyRepository;
import gg.modl.backend.support.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ServerKeyLastSeenTest {
    private final ServerKeyRepository repository = mock(ServerKeyRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-22T12:00:00Z"));
    private final ServerKeyLastSeen lastSeen = new ServerKeyLastSeen(repository, new DeferredUnitOfWork(),
        mock(ScheduledDatabaseJobs.class), clock);

    @Test
    void aRevokedKeyIsNoLongerTrackedOrWritten() {
        lastSeen.touch("revoked-key");
        lastSeen.touch("kept-key");

        lastSeen.onRevoked(new ServerKeyRevokedEvent(key("revoked-key")));
        lastSeen.writePending();

        verify(repository, never()).recordSeen(eq("revoked-key"), any(), any());
        verify(repository).recordSeen(eq("kept-key"), any(), any());
    }

    @Test
    void aKeyRevokedAfterItsLastWriteIsWrittenAgainIfItReturns() {
        lastSeen.touch("returning-key");
        lastSeen.writePending();
        lastSeen.onRevoked(new ServerKeyRevokedEvent(key("returning-key")));

        lastSeen.touch("returning-key");
        lastSeen.writePending();

        verify(repository, times(2)).recordSeen(anyString(), any(), any());
    }

    @Test
    void aFailedWriteKeepsTheKeyForTheNextFlush() {
        lastSeen.touch("busy-key");
        when(repository.recordSeen(eq("busy-key"), any(), any())).thenThrow(new IllegalStateException("busy")).thenReturn(true);

        assertThatThrownBy(lastSeen::writePending).isInstanceOf(IllegalStateException.class);
        lastSeen.writePending();
        lastSeen.writePending();

        verify(repository, times(2)).recordSeen(eq("busy-key"), any(), any());
    }

    private static ServerKey key(String id) {
        return new ServerKey(id, "lobby", ServerKeyScope.BEHIND_PROXY, false, "l".repeat(64), "abcdefgh", null, Instant.EPOCH, null);
    }
}
