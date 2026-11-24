package gg.modl.backend.iplookup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.iplookup.http.IpLookupResponse;
import gg.modl.backend.iplookup.http.IpLookupTransport;
import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.IpLookup;
import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.iplookup.provider.IpLookupCall;
import gg.modl.backend.iplookup.provider.IpLookupClients;
import gg.modl.backend.iplookup.provider.KeylessProxycheckClient;
import gg.modl.backend.iplookup.provider.VpnapiClient;
import gg.modl.backend.iplookup.repository.IpLookupRepository;
import gg.modl.backend.iplookup.settings.PlayerAddressSettings;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;
import gg.modl.backend.support.MutableClock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IpLookupServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ADDRESS = "203.0.113.7";
    private static final String CLEAN_BODY = "{\"ip\":\"203.0.113.7\",\"security\":{\"vpn\":false,\"proxy\":false,"
        + "\"tor\":false,\"relay\":false},\"location\":{\"country_code\":\"NL\"},\"network\":{}}";
    private static final PlayerAddressSettings VPNAPI_ACTIVE = PlayerAddressSettings.EMPTY
        .withKey(IpLookupProvider.VPNAPI, "key")
        .withProvider(IpLookupProvider.VPNAPI);
    private static final PlayerAddressSettings KEYLESS_ACTIVE = PlayerAddressSettings.EMPTY
        .withProvider(IpLookupProvider.PROXYCHECK_KEYLESS);
    private static final String KEYLESS_CLEAN_BODY = "{\"status\":\"ok\",\"203.0.113.7\":{\"proxy\":\"no\",\"type\":\"Residential\"}}";
    private static final String KEYLESS_EXHAUSTED_BODY = "{\"status\":\"denied\",\"message\":\"100 queries exhausted.\"}";

    private final MutableClock clock = new MutableClock(NOW);
    private final IpLookupRepository repository = mock(IpLookupRepository.class);
    private final PlayerAddressSettingsStore settings = mock(PlayerAddressSettingsStore.class);
    private final IpLookupTransport transport = mock(IpLookupTransport.class);
    private final IpLookupListener listener = mock(IpLookupListener.class);
    private final IpLookupBackoff backoff = new IpLookupBackoff(clock);
    private final OperatorNotice operatorNotice = mock(OperatorNotice.class);
    private final IpLookupService service = new IpLookupService(repository, settings, transport, backoff,
        new IpLookupClients(List.of(new VpnapiClient(), new KeylessProxycheckClient())), List.of(listener),
        operatorNotice, clock);

    @AfterEach
    void stop() throws InterruptedException {
        service.destroy();
    }

    @Test
    void nothingIsLookedUpUntilTheOwnerChoosesAProvider() throws IOException {
        when(settings.current()).thenReturn(PlayerAddressSettings.EMPTY.withKey(IpLookupProvider.VPNAPI, "key"));

        service.refreshWhenStale(ADDRESS, Optional.empty());

        verify(transport, never()).exchange(any());
    }

    @Test
    void aFreshCachedLookupIsNotRepeated() throws IOException {
        service.refreshWhenStale(ADDRESS, Optional.of(lookup(NOW.minus(Duration.ofDays(29)))));

        verify(transport, never()).exchange(any());
    }

    @Test
    void privateAddressesNeverLeaveTheServer() throws IOException {
        when(settings.current()).thenReturn(VPNAPI_ACTIVE);

        service.refreshWhenStale("192.168.1.4", Optional.empty());
        service.refreshWhenStale("127.0.0.1", Optional.empty());

        verify(transport, never()).exchange(any());
        assertThat(service.known("10.0.0.1")).isEmpty();
    }

    @Test
    void aStaleLookupIsRefreshedSavedAndAnnounced() throws IOException {
        when(settings.current()).thenReturn(VPNAPI_ACTIVE);
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(200, CLEAN_BODY));

        service.refreshWhenStale(ADDRESS, Optional.of(lookup(NOW.minus(Duration.ofDays(31)))));

        IpLookup expected = new IpLookup(ADDRESS, IpLookupProvider.VPNAPI,
            new IpFacts("NL", null, null, VpnStatus.NO, false), NOW);
        verify(repository, timeout(5_000)).save(expected);
        verify(listener, timeout(5_000)).resolved(expected);
    }

    @Test
    void aRefusedLookupPausesTheProviderAndStoresNothing() throws IOException {
        when(settings.current()).thenReturn(VPNAPI_ACTIVE);
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(429, "{}"));

        service.lookUp(ADDRESS, VPNAPI_ACTIVE.activeLookup().orElseThrow());

        assertThat(backoff.allows(IpLookupProvider.VPNAPI)).isFalse();
        verify(repository, never()).save(any());
        clock.advance(IpLookupBackoff.AFTER_REFUSAL);
        assertThat(backoff.allows(IpLookupProvider.VPNAPI)).isTrue();
    }

    @Test
    void anUnreachableProviderPausesBriefly() throws IOException {
        when(transport.exchange(any())).thenThrow(new IOException("connect timed out"));

        service.lookUp(ADDRESS, VPNAPI_ACTIVE.activeLookup().orElseThrow());

        assertThat(backoff.allows(IpLookupProvider.VPNAPI)).isFalse();
        clock.advance(IpLookupBackoff.AFTER_FAILURE);
        assertThat(backoff.allows(IpLookupProvider.VPNAPI)).isTrue();
    }

    @Test
    void keylessLookupsRunWithoutAKeyAndAreCachedLikeAnyOther() throws IOException {
        when(settings.current()).thenReturn(KEYLESS_ACTIVE);
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(200, KEYLESS_CLEAN_BODY));

        service.refreshWhenStale(ADDRESS, Optional.empty());

        IpLookup expected = new IpLookup(ADDRESS, IpLookupProvider.PROXYCHECK_KEYLESS,
            new IpFacts(null, null, null, VpnStatus.NO, false), NOW);
        verify(repository, timeout(5_000)).save(expected);
        ArgumentCaptor<IpLookupCall> call = ArgumentCaptor.forClass(IpLookupCall.class);
        verify(transport, timeout(5_000)).exchange(call.capture());
        assertThat(call.getValue().uri().toString()).isEqualTo("https://proxycheck.io/v2/203.0.113.7?vpn=1&asn=1");
    }

    @Test
    void theKeylessDailyLimitStopsLookupsUntilTheNextUtcDay() throws IOException {
        clock.advance(Duration.ofHours(15).plusMinutes(30));
        when(settings.current()).thenReturn(KEYLESS_ACTIVE);
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(200, KEYLESS_EXHAUSTED_BODY));

        service.lookUp(ADDRESS, KEYLESS_ACTIVE.activeLookup().orElseThrow());
        service.refreshWhenStale("198.51.100.20", Optional.empty());

        verify(transport, times(1)).exchange(any());
        verify(repository, never()).save(any());
        clock.advance(Duration.ofHours(8).plusMinutes(29).plusSeconds(59));
        assertThat(backoff.allows(IpLookupProvider.PROXYCHECK_KEYLESS)).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(backoff.allows(IpLookupProvider.PROXYCHECK_KEYLESS)).isTrue();
    }

    @Test
    void theConsoleHearsAboutTheKeylessDailyLimitOnceADay() throws IOException {
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(200, KEYLESS_EXHAUSTED_BODY));
        IpLookupAccess keyless = KEYLESS_ACTIVE.activeLookup().orElseThrow();

        service.lookUp(ADDRESS, keyless);
        service.lookUp("198.51.100.20", keyless);
        verify(operatorNotice, times(1)).publish(anyString(), any(Object[].class));

        clock.advance(Duration.ofDays(1));
        service.lookUp(ADDRESS, keyless);
        verify(operatorNotice, times(2)).publish(anyString(), any(Object[].class));
    }

    @Test
    void aKeyedRefusalIsNotAConsoleNotice() throws IOException {
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(401, "{}"));

        service.lookUp(ADDRESS, VPNAPI_ACTIVE.activeLookup().orElseThrow());

        verify(operatorNotice, never()).publish(anyString(), any(Object[].class));
    }

    @Test
    void aKeyedRefusalStillPausesForAnHourNotADay() throws IOException {
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(403, "{}"));

        service.lookUp(ADDRESS, VPNAPI_ACTIVE.activeLookup().orElseThrow());
        clock.advance(IpLookupBackoff.AFTER_REFUSAL);

        assertThat(backoff.allows(IpLookupProvider.VPNAPI)).isTrue();
    }

    private static IpLookup lookup(Instant at) {
        return new IpLookup(ADDRESS, IpLookupProvider.VPNAPI, new IpFacts("NL", null, null, VpnStatus.NO, false), at);
    }
}
