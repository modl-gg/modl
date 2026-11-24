package gg.modl.backend.iplookup.model;

import static gg.modl.backend.database.jooq.Tables.IP_LOOKUPS;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.TextLimits;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class IpLookupProviderTest {
    @ParameterizedTest
    @EnumSource(IpLookupProvider.class)
    void everyProviderIdFitsTheStoredProviderColumn(IpLookupProvider provider) {
        assertThat(TextLimits.require(IP_LOOKUPS.PROVIDER, provider.id())).isEqualTo(provider.id());
    }

    @ParameterizedTest
    @EnumSource(IpLookupProvider.class)
    void everyProviderIdReadsBackAsItsProvider(IpLookupProvider provider) {
        assertThat(IpLookupProvider.ofId(provider.id())).contains(provider);
    }
}
