package gg.modl.backend.iplookup.service;

import gg.modl.address.AddressScope;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.PanelLocations;
import gg.modl.backend.iplookup.http.IpLookupResponse;
import gg.modl.backend.iplookup.http.IpLookupTransport;
import gg.modl.backend.iplookup.model.IpLookup;
import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.provider.IpLookupAnswer;
import gg.modl.backend.iplookup.provider.IpLookupClient;
import gg.modl.backend.iplookup.provider.IpLookupClients;
import gg.modl.backend.iplookup.repository.IpLookupRepository;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class IpLookupService implements DisposableBean {
    static final int LOOKUP_THREADS = 2;
    static final int LOOKUP_QUEUE = 512;
    static final int KEYLESS_DAILY_LIMIT = 100;
    private static final long KEEP_ALIVE_SECONDS = 60;
    private static final long SHUTDOWN_SECONDS = 5;

    private final IpLookupRepository repository;
    private final PlayerAddressSettingsStore settings;
    private final IpLookupTransport transport;
    private final IpLookupBackoff backoff;
    private final IpLookupClients clients;
    private final List<IpLookupListener> listeners;
    private final OperatorNotice operatorNotice;
    private final Clock clock;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor;

    public IpLookupService(IpLookupRepository repository, PlayerAddressSettingsStore settings, IpLookupTransport transport,
                           IpLookupBackoff backoff, IpLookupClients clients, List<IpLookupListener> listeners,
                           OperatorNotice operatorNotice, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.transport = transport;
        this.backoff = backoff;
        this.clients = clients;
        this.listeners = List.copyOf(listeners);
        this.operatorNotice = operatorNotice;
        this.clock = clock;
        this.executor = newExecutor();
    }

    public Optional<IpLookup> known(String ip) {
        return AddressScope.isPublicLiteral(ip) ? repository.find(ip) : Optional.empty();
    }

    public void refreshWhenStale(String ip, Optional<IpLookup> known) {
        if (known.isPresent() && known.get().isFreshAt(clock.instant())) {
            return;
        }
        Optional<IpLookupAccess> active = settings.current().activeLookup();
        if (active.isEmpty() || !AddressScope.isPublicLiteral(ip) || !backoff.allows(active.get().provider())
            || !inFlight.add(ip)) {
            return;
        }
        try {
            executor.execute(() -> lookUp(ip, active.get()));
        } catch (RejectedExecutionException saturated) {
            inFlight.remove(ip);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS);
    }

    void lookUp(String ip, IpLookupAccess active) {
        try {
            Optional<IpLookupClient> found = clients.of(active.provider());
            if (found.isEmpty()) {
                return;
            }
            IpLookupClient client = found.get();
            IpLookupResponse response = transport.exchange(client.call(ip, active));
            handle(ip, active.provider(), client.read(response.status(), response.body()));
        } catch (IOException | RuntimeException failure) {
            backoff.pauseAfterFailure(active.provider());
            log.debug("IP lookup through {} failed: {}", active.provider().id(), failure.getMessage());
        } finally {
            inFlight.remove(ip);
        }
    }

    private void handle(String ip, IpLookupProvider provider, IpLookupAnswer answer) {
        switch (answer) {
            case IpLookupAnswer.Found found -> {
                backoff.clear(provider);
                IpLookup lookup = new IpLookup(ip, provider, found.facts(), clock.instant());
                repository.save(lookup);
                listeners.forEach(listener -> listener.resolved(lookup));
            }
            case IpLookupAnswer.Refused refused -> pauseAfterRefusal(provider, refused);
            case IpLookupAnswer.Failed failed -> {
                backoff.pauseAfterFailure(provider);
                log.debug("IP lookup failed: {}", failed.reason());
            }
        }
    }

    private void pauseAfterRefusal(IpLookupProvider provider, IpLookupAnswer.Refused refused) {
        if (!provider.needsKey()) {
            if (backoff.pauseUntilNextDay(provider)) {
                operatorNotice.publish("proxycheck.io without a key reached its limit of {} lookups today for this "
                    + "server's address. New player addresses stay unknown, so they cannot carry alt bans, until "
                    + "tomorrow (UTC). A free proxycheck.io key raises the limit: {}.", KEYLESS_DAILY_LIMIT,
                    PanelLocations.PLAYER_ADDRESSES);
            }
            return;
        }
        if (backoff.pauseAfterRefusal(provider)) {
            log.warn("{}; IP lookups pause for an hour. Check the key and the daily limit in {}", refused.reason(),
                PanelLocations.PLAYER_ADDRESSES);
        }
    }

    private static ThreadPoolExecutor newExecutor() {
        AtomicInteger threads = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(LOOKUP_THREADS, LOOKUP_THREADS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(LOOKUP_QUEUE), runnable -> {
                Thread thread = new Thread(runnable, "modl-ip-lookup-" + threads.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
