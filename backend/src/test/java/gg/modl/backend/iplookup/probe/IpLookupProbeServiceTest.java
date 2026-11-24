package gg.modl.backend.iplookup.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.address.AddressScope;
import gg.modl.backend.iplookup.http.IpLookupResponse;
import gg.modl.backend.iplookup.http.IpLookupTransport;
import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.provider.IpLookupCall;
import gg.modl.backend.iplookup.provider.IpLookupClients;
import gg.modl.backend.iplookup.provider.IphubClient;
import gg.modl.backend.iplookup.provider.KeylessProxycheckClient;
import gg.modl.backend.iplookup.provider.ProxycheckClient;
import gg.modl.backend.iplookup.service.IpLookupBackoff;
import gg.modl.backend.iplookup.settings.PlayerAddressSettings;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;
import gg.modl.backend.support.MutableClock;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IpLookupProbeServiceTest {
    private static final IpLookupAccess IPHUB = IpLookupAccess.keyed(IpLookupProvider.IPHUB, "hub-key");
    private static final IpLookupAccess PROXYCHECK = IpLookupAccess.keyed(IpLookupProvider.PROXYCHECK, "pc-key");
    private static final IpLookupAccess KEYLESS = IpLookupAccess.keyless(IpLookupProvider.PROXYCHECK_KEYLESS);
    private static final String KEYLESS_CLEAN = "{\"status\":\"ok\",\"1.1.1.1\":{\"proxy\":\"no\",\"type\":\"Business\"}}";

    private final IpLookupTransport transport = mock(IpLookupTransport.class);
    private final PlayerAddressSettingsStore settings = mock(PlayerAddressSettingsStore.class);
    private final IpLookupBackoff backoff = new IpLookupBackoff(new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));
    private final IpLookupProbeService probe = new IpLookupProbeService(
        new IpLookupClients(List.of(new IphubClient(), new ProxycheckClient(), new KeylessProxycheckClient())),
        transport, backoff, settings);

    @BeforeEach
    void noProviderChosen() {
        when(settings.current()).thenReturn(PlayerAddressSettings.EMPTY);
    }

    @Test
    void theTestAddressIsAFixedPublicAddressAndNoPlayerAddressIsSent() throws IOException {
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(200, "{\"countryCode\":\"AU\",\"block\":1}"));

        IpLookupProbeResult result = probe.test(IPHUB);

        ArgumentCaptor<IpLookupCall> call = ArgumentCaptor.forClass(IpLookupCall.class);
        verify(transport).exchange(call.capture());
        assertThat(call.getValue().uri().toString()).isEqualTo("https://v2.api.iphub.info/ip/" + IpLookupProbeService.TEST_ADDRESS);
        assertThat(AddressScope.isPublicLiteral(IpLookupProbeService.TEST_ADDRESS)).isTrue();
        assertThat(result.testAddress()).isEqualTo(IpLookupProbeService.TEST_ADDRESS);
        assertThat(result.outcome()).isEqualTo(IpLookupProbeOutcome.WORKING);
        assertThat(result.upstreamStatus()).isEqualTo(200);
        assertThat(result.keyChecked()).isTrue();
    }

    @Test
    void aRefusedKeyIsReportedAsRefused() throws IOException {
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(403, ""));

        assertThat(probe.test(IPHUB).outcome()).isEqualTo(IpLookupProbeOutcome.REFUSED);
    }

    @Test
    void aKeylessRefusalIsTheDailyLimit() throws IOException {
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(200, "{\"status\":\"denied\"}"));

        assertThat(probe.test(KEYLESS).outcome()).isEqualTo(IpLookupProbeOutcome.DAILY_LIMIT_REACHED);
    }

    @Test
    void anAnswerWithoutAVpnVerdictIsNotCalledWorking() throws IOException {
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(200, "{\"countryCode\":\"AU\",\"block\":2}"));

        assertThat(probe.test(IPHUB).outcome()).isEqualTo(IpLookupProbeOutcome.NO_VERDICT);
    }

    @Test
    void anUnreadableAnswerAndAnUnreachableProviderAreTold() throws IOException {
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(502, "<html>"));
        assertThat(probe.test(IPHUB).outcome()).isEqualTo(IpLookupProbeOutcome.UNEXPECTED_ANSWER);

        when(transport.exchange(any())).thenThrow(new IOException("connect timed out"));
        IpLookupProbeResult unreachable = probe.test(IPHUB);
        assertThat(unreachable.outcome()).isEqualTo(IpLookupProbeOutcome.UNREACHABLE);
        assertThat(unreachable.upstreamStatus()).isNull();
    }

    @Test
    void proxycheckCannotConfirmAKeyBecauseItAnswersUnknownKeysToo() throws IOException {
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(200, KEYLESS_CLEAN));

        IpLookupProbeResult result = probe.test(PROXYCHECK);

        assertThat(result.outcome()).isEqualTo(IpLookupProbeOutcome.WORKING);
        assertThat(result.keyChecked()).isFalse();
    }

    @Test
    void aWorkingTestOfTheActiveLookupResumesPausedLookups() throws IOException {
        when(settings.current()).thenReturn(PlayerAddressSettings.EMPTY.withProvider(IpLookupProvider.PROXYCHECK_KEYLESS));
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(200, KEYLESS_CLEAN));
        backoff.pauseUntilNextDay(IpLookupProvider.PROXYCHECK_KEYLESS);

        probe.test(KEYLESS);

        assertThat(backoff.allows(IpLookupProvider.PROXYCHECK_KEYLESS)).isTrue();
    }

    @Test
    void aWorkingTestOfADraftKeyLeavesThePauseOfTheSavedKeyAlone() throws IOException {
        when(settings.current()).thenReturn(PlayerAddressSettings.EMPTY.withKey(IpLookupProvider.IPHUB, "saved")
            .withProvider(IpLookupProvider.IPHUB));
        when(transport.exchange(any())).thenReturn(new IpLookupResponse(200, "{\"countryCode\":\"AU\",\"block\":0}"));
        backoff.pauseAfterRefusal(IpLookupProvider.IPHUB);

        probe.test(IPHUB);

        assertThat(backoff.allows(IpLookupProvider.IPHUB)).isFalse();
    }
}
