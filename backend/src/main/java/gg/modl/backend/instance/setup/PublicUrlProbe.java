package gg.modl.backend.instance.setup;

import gg.modl.backend.infrastructure.exception.TooManyRequestsException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PublicUrlProbe {
    static final String PROBE_PATH = "/probe/";
    private static final List<String> LOOPBACK_ADDRESSES = List.of("127.0.0.1", "[::1]");
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration NONCE_LIFETIME = Duration.ofSeconds(10);
    private static final int MAX_OUTSTANDING_PROBES = 32;

    private final IdGenerator idGenerator;
    private final Clock clock;
    private final HttpClient httpClient;
    private final Map<String, Instant> outstanding = new ConcurrentHashMap<>();

    @Autowired
    public PublicUrlProbe(IdGenerator idGenerator, Clock clock) {
        this(idGenerator, clock, HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .build());
    }

    PublicUrlProbe(IdGenerator idGenerator, Clock clock, HttpClient httpClient) {
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.httpClient = httpClient;
    }

    public PublicUrlCheck check(String url) {
        Optional<HttpOrigin> origin = HttpOrigin.parseExact(url);
        if (origin.isEmpty()) {
            return PublicUrlCheck.invalid();
        }
        return new PublicUrlCheck(probe(origin.get()), origin.get().value());
    }

    public boolean recognizes(String nonce) {
        Instant issuedAt = outstanding.get(nonce);
        return issuedAt != null && issuedAt.plus(NONCE_LIFETIME).isAfter(clock.instant());
    }

    private PublicUrlVerdict probe(HttpOrigin origin) {
        Instant now = clock.instant();
        outstanding.values().removeIf(issuedAt -> !issuedAt.plus(NONCE_LIFETIME).isAfter(now));
        if (outstanding.size() >= MAX_OUTSTANDING_PROBES) {
            throw new TooManyRequestsException("Too many address checks are running; try again in a few seconds");
        }
        String nonce = idGenerator.generateToken();
        outstanding.put(nonce, now);
        try {
            PublicUrlVerdict verdict = PublicUrlVerdict.UNREACHABLE;
            for (HttpOrigin candidate : addressesOf(origin)) {
                PublicUrlVerdict answered = send(candidate, nonce);
                if (answered == PublicUrlVerdict.REACHABLE) {
                    return answered;
                }
                if (answered == PublicUrlVerdict.OTHER_SERVICE) {
                    verdict = answered;
                }
            }
            return verdict;
        } finally {
            outstanding.remove(nonce);
        }
    }

    static List<HttpOrigin> addressesOf(HttpOrigin origin) {
        if (!origin.namesLoopback()) {
            return List.of(origin);
        }
        List<HttpOrigin> addresses = new ArrayList<>();
        addresses.add(origin);
        LOOPBACK_ADDRESSES.forEach(address -> addresses.add(origin.withHost(address)));
        return List.copyOf(addresses);
    }

    private PublicUrlVerdict send(HttpOrigin origin, String nonce) {
        try {
            HttpResponse<Void> response = httpClient.send(probeRequest(origin, nonce), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == HttpStatus.NO_CONTENT.value() ? PublicUrlVerdict.REACHABLE : PublicUrlVerdict.OTHER_SERVICE;
        } catch (IOException e) {
            return PublicUrlVerdict.UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PublicUrlVerdict.UNREACHABLE;
        }
    }

    @PreDestroy
    void close() {
        httpClient.close();
    }

    private static HttpRequest probeRequest(HttpOrigin origin, String nonce) {
        return HttpRequest.newBuilder(URI.create(origin.value() + RESTMappingV1.PUBLIC_SETUP + PROBE_PATH + nonce))
            .timeout(TIMEOUT)
            .GET()
            .build();
    }
}
