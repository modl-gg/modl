package gg.modl.backend.ai.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelInfo;
import gg.modl.backend.ai.model.AiModelListing;
import gg.modl.backend.ai.model.AiModelMetadata;
import gg.modl.backend.ai.protocol.AiProtocolClients;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class AiModelCatalogService implements AiModelMetadata {
    private static final int CAPACITY = 32;
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Duration FRESHNESS = Duration.ofHours(6);
    private static final Duration FETCH_DEADLINE = Duration.ofSeconds(20);
    private static final Set<AiFailureKind> NEVER_STALE = Set.of(AiFailureKind.AUTH, AiFailureKind.PERMISSION);
    private static final Comparator<AiModelInfo> BY_ID = Comparator.comparing(model -> model.id().toLowerCase(Locale.ROOT));

    private final AiProtocolClients clients;
    private final Clock clock;
    private final Cache<String, Listed> listings = Caffeine.newBuilder()
        .maximumSize(CAPACITY)
        .expireAfterWrite(RETENTION)
        .build();
    private final ConcurrentMap<String, CompletableFuture<AiModelCatalog>> inFlight = new ConcurrentHashMap<>();

    public AiModelCatalogService(AiProtocolClients clients, Clock clock) {
        this.clients = clients;
        this.clock = clock;
    }

    public AiModelCatalog list(AiConnection connection, boolean refresh) {
        String key = connection.credentialFingerprint();
        Listed cached = listings.getIfPresent(key);
        if (!refresh && cached != null && cached.fetchedAt().plus(FRESHNESS).isAfter(clock.instant())) {
            return cached.toCatalog(false, null);
        }
        CompletableFuture<AiModelCatalog> mine = new CompletableFuture<>();
        CompletableFuture<AiModelCatalog> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return running.join();
        }
        try {
            AiModelCatalog fetched = fetch(connection, key);
            mine.complete(fetched);
            return fetched;
        } catch (RuntimeException failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    @Override
    public Optional<AiModelInfo> cached(AiConnection connection) {
        Listed cached = listings.getIfPresent(connection.credentialFingerprint());
        if (cached == null || connection.model() == null) {
            return Optional.empty();
        }
        return cached.models().stream().filter(model -> model.id().equals(connection.model())).findFirst();
    }

    private AiModelCatalog fetch(AiConnection connection, String key) {
        AiModelListing listing = clients.forProtocol(connection.provider().protocol())
            .listModels(connection, AiDeadline.after(FETCH_DEADLINE));
        AiFailureKind failure = listing.failure();
        if (failure != null) {
            Listed previous = listings.getIfPresent(key);
            if (previous == null || NEVER_STALE.contains(failure)) {
                return new AiModelCatalog(List.of(), false, null, failure, false);
            }
            return previous.toCatalog(true, failure);
        }
        List<AiModelInfo> models = listing.models().stream().map(ChatModelFilter::classify).sorted(BY_ID).toList();
        Listed fresh = new Listed(models, clock.instant(), listing.complete());
        listings.put(key, fresh);
        return fresh.toCatalog(false, null);
    }

    private record Listed(List<AiModelInfo> models, Instant fetchedAt, boolean complete) {
        AiModelCatalog toCatalog(boolean stale, @Nullable AiFailureKind failure) {
            return new AiModelCatalog(models, stale, fetchedAt, failure, complete && !stale);
        }
    }
}
