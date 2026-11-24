package gg.modl.backend.serverkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyChangedEvent;
import gg.modl.backend.serverkey.ServerKeyRevokedEvent;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.serverkey.repository.ServerKeyRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServerKeyCacheTest {
    private static final ServerKey KEY =
        new ServerKey("k1", "lobby", ServerKeyScope.BEHIND_PROXY, false, "h".repeat(64), "abcdefgh", null, Instant.EPOCH, null);

    private final ServerKeyRepository repository = mock(ServerKeyRepository.class);
    private final ServerKeyCache cache = new ServerKeyCache(repository);

    @Test
    void lookupsAreServedFromTheCacheUntilTheKeyChanges() {
        when(repository.findByKeyHash(KEY.keyHash())).thenReturn(Optional.of(KEY));

        assertThat(cache.findByHash(KEY.keyHash())).contains(KEY);
        assertThat(cache.findByHash(KEY.keyHash())).contains(KEY);
        verify(repository, times(1)).findByKeyHash(KEY.keyHash());

        cache.onChanged(new ServerKeyChangedEvent(KEY.withAllowProxyCommands(true)));
        cache.findByHash(KEY.keyHash());
        verify(repository, times(2)).findByKeyHash(KEY.keyHash());
    }

    @Test
    void aRevokedKeyIsLookedUpAgain() {
        when(repository.findById("k1")).thenReturn(Optional.of(KEY)).thenReturn(Optional.empty());

        assertThat(cache.findById("k1")).contains(KEY);
        cache.onRevoked(new ServerKeyRevokedEvent(KEY));

        assertThat(cache.findById("k1")).isEmpty();
    }

    @Test
    void unknownKeysAreCachedToo() {
        when(repository.findByKeyHash("unknown")).thenReturn(Optional.empty());

        assertThat(cache.findByHash("unknown")).isEmpty();
        assertThat(cache.findByHash("unknown")).isEmpty();
        verify(repository, times(1)).findByKeyHash("unknown");
    }
}
