package gg.modl.backend.iplookup.probe;

import gg.modl.backend.iplookup.http.IpLookupResponse;
import gg.modl.backend.iplookup.http.IpLookupTransport;
import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.iplookup.provider.IpLookupAnswer;
import gg.modl.backend.iplookup.provider.IpLookupClient;
import gg.modl.backend.iplookup.provider.IpLookupClients;
import gg.modl.backend.iplookup.service.IpLookupBackoff;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class IpLookupProbeService {
    public static final String TEST_ADDRESS = "1.1.1.1";
    static final int CONCURRENT_PROBES = 2;

    private final IpLookupClients clients;
    private final IpLookupTransport transport;
    private final IpLookupBackoff backoff;
    private final PlayerAddressSettingsStore settings;
    private final Semaphore probes = new Semaphore(CONCURRENT_PROBES);

    public IpLookupProbeService(IpLookupClients clients, IpLookupTransport transport, IpLookupBackoff backoff,
                                PlayerAddressSettingsStore settings) {
        this.clients = clients;
        this.transport = transport;
        this.backoff = backoff;
        this.settings = settings;
    }

    public IpLookupProbeResult test(IpLookupAccess access) {
        if (!probes.tryAcquire()) {
            return result(access, IpLookupProbeOutcome.BUSY, Duration.ZERO, null);
        }
        long started = System.nanoTime();
        try {
            return probe(access, started);
        } finally {
            probes.release();
        }
    }

    private IpLookupProbeResult probe(IpLookupAccess access, long started) {
        Optional<IpLookupClient> client = clients.of(access.provider());
        if (client.isEmpty()) {
            return result(access, IpLookupProbeOutcome.UNREACHABLE, elapsed(started), null);
        }
        IpLookupResponse response;
        try {
            response = transport.exchange(client.get().call(TEST_ADDRESS, access));
        } catch (IOException unreachable) {
            return result(access, IpLookupProbeOutcome.UNREACHABLE, elapsed(started), null);
        }
        IpLookupProbeOutcome outcome = outcomeOf(access.provider(), client.get().read(response.status(), response.body()));
        if (outcome == IpLookupProbeOutcome.WORKING && settings.current().activeLookup().filter(access::equals).isPresent()) {
            backoff.clear(access.provider());
        }
        return result(access, outcome, elapsed(started), response.status());
    }

    private static IpLookupProbeOutcome outcomeOf(IpLookupProvider provider, IpLookupAnswer answer) {
        return switch (answer) {
            case IpLookupAnswer.Found found -> found.facts().vpn() == VpnStatus.UNKNOWN
                ? IpLookupProbeOutcome.NO_VERDICT
                : IpLookupProbeOutcome.WORKING;
            case IpLookupAnswer.Refused refused -> provider.needsKey()
                ? IpLookupProbeOutcome.REFUSED
                : IpLookupProbeOutcome.DAILY_LIMIT_REACHED;
            case IpLookupAnswer.Failed failed -> IpLookupProbeOutcome.UNEXPECTED_ANSWER;
        };
    }

    private static IpLookupProbeResult result(IpLookupAccess access, IpLookupProbeOutcome outcome, Duration latency,
                                              @Nullable Integer upstreamStatus) {
        return new IpLookupProbeResult(outcome, TEST_ADDRESS, latency, upstreamStatus,
            access.provider().refusesUnknownKeys());
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
