package gg.modl.backend.settings.controller;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.probe.IpLookupProbeOutcome;
import gg.modl.backend.iplookup.probe.IpLookupProbeResult;
import gg.modl.backend.iplookup.settings.AddressForwardingNotice;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsView;
import gg.modl.proto.modl.v1.PlayerAddressLookupTestOutcome;
import gg.modl.proto.modl.v1.PlayerAddressLookupTestResponse;
import gg.modl.proto.modl.v1.PlayerAddressSettingsResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerAddressSettingsProtoMapperTest {
    @Test
    void theKeylessChoiceIsListedAndNothingIsPreselected() {
        PlayerAddressSettingsResponse response = PlayerAddressSettingsProtoMapper.toResponse(new PlayerAddressSettingsView(
            null, List.of(), false, true, 10, AddressForwardingNotice.NONE));

        assertThat(response.getKeylessProvidersList()).containsExactly(IpLookupProvider.PROXYCHECK_KEYLESS.id());
        assertThat(response.getProvidersList()).contains(IpLookupProvider.PROXYCHECK_KEYLESS.id());
        assertThat(response.hasProvider()).isFalse();
        assertThat(response.getLookupsActive()).isFalse();
        assertThat(response.getLookupChoicePending()).isTrue();
    }

    @Test
    void theTestResultCarriesTheOutcomeTheAddressAndWhetherTheKeyWasChecked() {
        PlayerAddressLookupTestResponse response = PlayerAddressSettingsProtoMapper.toResponse(new IpLookupProbeResult(
            IpLookupProbeOutcome.DAILY_LIMIT_REACHED, "1.1.1.1", Duration.ofMillis(120), 429, false));

        assertThat(response.getOutcome()).isEqualTo(PlayerAddressLookupTestOutcome.PLAYER_ADDRESS_LOOKUP_TEST_OUTCOME_DAILY_LIMIT_REACHED);
        assertThat(response.getTestAddress()).isEqualTo("1.1.1.1");
        assertThat(response.getLatencyMs()).isEqualTo(120);
        assertThat(response.getUpstreamStatus()).isEqualTo(429);
        assertThat(response.getKeyChecked()).isFalse();
    }

    @Test
    void everyProbeOutcomeHasAWireValue() {
        for (IpLookupProbeOutcome outcome : IpLookupProbeOutcome.values()) {
            PlayerAddressLookupTestResponse response = PlayerAddressSettingsProtoMapper.toResponse(
                new IpLookupProbeResult(outcome, "1.1.1.1", Duration.ZERO, null, true));

            assertThat(response.getOutcome()).isNotEqualTo(PlayerAddressLookupTestOutcome.PLAYER_ADDRESS_LOOKUP_TEST_OUTCOME_UNSPECIFIED);
            assertThat(response.hasUpstreamStatus()).isFalse();
        }
    }
}
