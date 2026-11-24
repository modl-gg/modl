package gg.modl.backend.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.repository.InstanceRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InstanceContextTest {

    @Test
    void aSlowEarlierReadNeverOverwritesALaterRefresh() throws Exception {
        Server stale = TestServers.server("Stale", "owner@example.com");
        Server fresh = TestServers.server("Fresh", "owner@example.com");
        CountDownLatch staleReadStarted = new CountDownLatch(1);
        CountDownLatch finishStaleRead = new CountDownLatch(1);
        InstanceRepository repository = mock(InstanceRepository.class);
        when(repository.find())
            .thenAnswer(invocation -> {
                staleReadStarted.countDown();
                finishStaleRead.await(5, TimeUnit.SECONDS);
                return Optional.of(stale);
            })
            .thenReturn(Optional.of(fresh));
        InstanceContext context = new InstanceContext(repository);

        CompletableFuture<Server> slowRefresh = CompletableFuture.supplyAsync(context::refresh);
        assertThat(staleReadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(context.refresh().getServerName()).isEqualTo("Fresh");
        finishStaleRead.countDown();

        assertThat(slowRefresh.get(5, TimeUnit.SECONDS).getServerName()).isEqualTo("Fresh");
        assertThat(context.instance().getServerName()).isEqualTo("Fresh");
    }
}
