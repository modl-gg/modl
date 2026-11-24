package gg.modl.backend.iplookup.settings;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.iplookup.model.IpLookupProvider;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlayerAddressSettingsTest {
    @Test
    void lookupsAreOffByDefaultWithATenAccountLimit() {
        assertThat(PlayerAddressSettings.EMPTY.activeLookup()).isEmpty();
        assertThat(PlayerAddressSettings.EMPTY.effectiveSharedAccountLimit()).isEqualTo(10);
    }

    @Test
    void aProviderIsActiveOnlyWithItsOwnKey() {
        PlayerAddressSettings chosen = PlayerAddressSettings.EMPTY
            .withKey(IpLookupProvider.IPHUB, "hub-key")
            .withProvider(IpLookupProvider.VPNAPI);

        assertThat(chosen.activeLookup()).isEmpty();
        assertThat(chosen.withKey(IpLookupProvider.VPNAPI, "vpn-key").activeLookup())
            .hasValueSatisfying(active -> {
                assertThat(active.provider()).isEqualTo(IpLookupProvider.VPNAPI);
                assertThat(active.apiKey()).isEqualTo("vpn-key");
            });
    }

    @Test
    void keylessProxycheckIsActiveWithoutAnyKey() {
        PlayerAddressSettings keyless = PlayerAddressSettings.EMPTY.withProvider(IpLookupProvider.PROXYCHECK_KEYLESS);

        assertThat(keyless.activeLookup()).hasValueSatisfying(active -> {
            assertThat(active.provider()).isEqualTo(IpLookupProvider.PROXYCHECK_KEYLESS);
            assertThat(active.apiKey()).isNull();
        });
        assertThat(keyless.canUse(IpLookupProvider.PROXYCHECK_KEYLESS)).isTrue();
        assertThat(keyless.canUse(IpLookupProvider.PROXYCHECK)).isFalse();
    }

    @Test
    void theChoiceIsPendingUntilTheOwnerDecidesEvenWhenTheDecisionIsNoLookups() {
        assertThat(PlayerAddressSettings.EMPTY.choicePending()).isTrue();
        assertThat(PlayerAddressSettings.EMPTY.withChoiceMade(Instant.EPOCH).choicePending()).isFalse();
        assertThat(PlayerAddressSettings.EMPTY.withProvider(IpLookupProvider.PROXYCHECK_KEYLESS).choicePending()).isFalse();
    }

    @Test
    void anAlreadyChosenProviderFromBeforeTheChoicePromptCountsAsDecided() {
        PlayerAddressSettings stored = new PlayerAddressSettings(IpLookupProvider.VPNAPI.id(), null, null, null);

        assertThat(stored.choicePending()).isFalse();
    }

    @Test
    void removingTheChosenProvidersKeyTurnsLookupsOff() {
        PlayerAddressSettings active = PlayerAddressSettings.EMPTY
            .withKey(IpLookupProvider.PROXYCHECK, "key")
            .withProvider(IpLookupProvider.PROXYCHECK);

        PlayerAddressSettings removed = active.withoutKey(IpLookupProvider.PROXYCHECK);

        assertThat(removed.selectedProvider()).isEmpty();
        assertThat(removed.hasKey(IpLookupProvider.PROXYCHECK)).isFalse();
    }

    @Test
    void anOutOfRangeStoredLimitFallsBackToTheDefault() {
        assertThat(PlayerAddressSettings.EMPTY.withSharedAccountLimit(1).effectiveSharedAccountLimit()).isEqualTo(10);
        assertThat(PlayerAddressSettings.EMPTY.withSharedAccountLimit(1001).effectiveSharedAccountLimit()).isEqualTo(10);
        assertThat(PlayerAddressSettings.EMPTY.withSharedAccountLimit(25).effectiveSharedAccountLimit()).isEqualTo(25);
    }

    @Test
    void keysNeverAppearInTheTextOfTheSettings() {
        PlayerAddressSettings settings = PlayerAddressSettings.EMPTY.withKey(IpLookupProvider.VPNAPI, "super-secret")
            .withProvider(IpLookupProvider.VPNAPI);

        assertThat(settings.toString()).doesNotContain("super-secret");
        assertThat(settings.activeLookup().orElseThrow().toString()).doesNotContain("super-secret");
    }
}
