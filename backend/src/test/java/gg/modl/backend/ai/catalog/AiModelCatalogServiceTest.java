package gg.modl.backend.ai.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.model.AiCompletionRequest;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelInfo;
import gg.modl.backend.ai.model.AiModelListing;
import gg.modl.backend.ai.model.AiOutcome;
import gg.modl.backend.ai.model.AiProtocol;
import gg.modl.backend.ai.model.AiProtocolClient;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.protocol.AiProtocolClients;
import gg.modl.backend.ai.provider.AiProviderCatalog;
import gg.modl.backend.support.MutableClock;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AiModelCatalogServiceTest {
    private static final AiProviderCatalog CATALOG = new AiProviderCatalog();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-22T00:00:00Z"));
    private final ListingClient client = new ListingClient();
    private final AiModelCatalogService service = new AiModelCatalogService(clients(client), clock);

    @Test
    void aFreshListIsServedFromTheCacheAndSortedById() {
        client.next(listing("b-model", "A-model", "c-embedding"));

        AiModelCatalog first = service.list(connection("key"), false);
        AiModelCatalog second = service.list(connection("key"), false);

        assertThat(first.models()).extracting(AiModelInfo::id).containsExactly("A-model", "b-model", "c-embedding");
        assertThat(first.models()).extracting(AiModelInfo::chatCapable).containsExactly(true, true, false);
        assertThat(second).isEqualTo(first);
        assertThat(client.calls).hasValue(1);
    }

    @Test
    void aStaleListIsRefetchedAndAFailureServesThePreviousOne() {
        client.next(listing("m1"));
        service.list(connection("key"), false);
        clock.advance(Duration.ofHours(7));
        client.next(AiModelListing.failed(AiFailureKind.PROVIDER_ERROR));

        AiModelCatalog served = service.list(connection("key"), false);

        assertThat(served.stale()).isTrue();
        assertThat(served.failure()).isEqualTo(AiFailureKind.PROVIDER_ERROR);
        assertThat(served.models()).extracting(AiModelInfo::id).containsExactly("m1");
        assertThat(client.calls).hasValue(2);
    }

    @Test
    void authenticationFailuresNeverServeAStaleList() {
        client.next(listing("m1"));
        service.list(connection("key"), false);
        client.next(AiModelListing.failed(AiFailureKind.AUTH));

        AiModelCatalog served = service.list(connection("key"), true);

        assertThat(served.models()).isEmpty();
        assertThat(served.failure()).isEqualTo(AiFailureKind.AUTH);
    }

    @Test
    void differentKeysHaveDifferentEntriesAndKeysAreNotCacheKeys() {
        client.next(listing("m1"));
        client.next(listing("m2"));

        service.list(connection("key-1"), false);
        AiModelCatalog other = service.list(connection("key-2"), false);

        assertThat(other.models()).extracting(AiModelInfo::id).containsExactly("m2");
        assertThat(connection("key-1").credentialFingerprint()).doesNotContain("key-1").hasSize(64);
    }

    @Test
    void concurrentRefreshesShareOneFetch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        client.blockOn(release);
        client.next(listing("m1"));
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            List<Future<AiModelCatalog>> calls = List.of(
                threads.submit(() -> service.list(connection("key"), true)),
                threads.submit(() -> service.list(connection("key"), true)),
                threads.submit(() -> service.list(connection("key"), true)));
            Thread.sleep(200);
            release.countDown();
            for (Future<AiModelCatalog> call : calls) {
                assertThat(call.get(10, TimeUnit.SECONDS).models()).hasSize(1);
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(client.calls).hasValue(1);
    }

    @Test
    void cachedMetadataIsAvailableForTheChosenModel() {
        client.next(listing("m1", "m2"));
        service.list(connection("key"), false);

        assertThat(service.cached(connection("key"))).map(AiModelInfo::id).contains("m1");
        assertThat(service.cached(connection("other"))).isEmpty();
    }

    static AiConnection connection(String key) {
        return new AiConnection(CATALOG.find("openai").orElseThrow(), URI.create("https://api.openai.com/v1"), key, "m1", null);
    }

    static AiModelListing listing(String... ids) {
        return new AiModelListing(List.of(ids).stream().map(id -> new AiModelInfo(id, null, true, false, null, null, null)).toList(),
            null, true);
    }

    static AiProtocolClients clients(AiProtocolClient openAi) {
        return new AiProtocolClients(List.of(openAi, new ListingClient(AiProtocol.ANTHROPIC_MESSAGES),
            new ListingClient(AiProtocol.GEMINI_GENERATE)));
    }

    static final class ListingClient implements AiProtocolClient {
        private final AiProtocol protocol;
        private final Deque<AiModelListing> listings = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private CountDownLatch gate = new CountDownLatch(0);

        ListingClient() {
            this(AiProtocol.OPENAI_CHAT);
        }

        ListingClient(AiProtocol protocol) {
            this.protocol = protocol;
        }

        void next(AiModelListing listing) {
            listings.add(listing);
        }

        void blockOn(CountDownLatch latch) {
            gate = latch;
        }

        @Override
        public AiProtocol protocol() {
            return protocol;
        }

        @Override
        public synchronized AiModelListing listModels(AiConnection connection, AiDeadline deadline) {
            calls.incrementAndGet();
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            AiModelListing next = listings.poll();
            return next != null ? next : AiModelListing.failed(AiFailureKind.PROVIDER_ERROR);
        }

        @Override
        public AiOutcome complete(AiConnection connection, AiCompletionRequest request, AiRequestShape shape, AiDeadline deadline) {
            throw new UnsupportedOperationException();
        }
    }
}
