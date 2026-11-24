package gg.modl.backend.iplookup.repository;

import static gg.modl.backend.player.repository.PlayerFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.IpLookup;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.io.TempDir;

class JooqIpLookupRepositoryContractTest {
    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aLookupRoundTripsAndASecondLookupReplacesIt(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqIpLookupRepository lookups = repository(database);
            IpLookup first = new IpLookup("203.0.113.7", IpLookupProvider.VPNAPI,
                new IpFacts("NL", "North Holland", "AS64500 Example", VpnStatus.UNKNOWN, false), NOW);
            IpLookup refreshed = new IpLookup("203.0.113.7", IpLookupProvider.IPAPI_IS,
                new IpFacts("NL", null, null, VpnStatus.NO, true), NOW.plus(Duration.ofDays(31)));

            lookups.save(first);
            assertThat(lookups.find("203.0.113.7")).contains(first);

            lookups.save(refreshed);
            assertThat(lookups.find("203.0.113.7")).contains(refreshed);
            assertThat(lookups.count()).isEqualTo(1);
            assertThat(lookups.find("203.0.113.8")).isEmpty();
        }
    }

    @SqlContractTest
    void overlongTextIsCappedAndBlankTextIsStoredAsMissing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqIpLookupRepository lookups = repository(database);
            lookups.save(new IpLookup("203.0.113.9", IpLookupProvider.PROXYCHECK,
                new IpFacts(" ", "r".repeat(500), "a".repeat(500), VpnStatus.YES, false), NOW));

            IpFacts stored = lookups.find("203.0.113.9").orElseThrow().facts();
            assertThat(stored.country()).isNull();
            assertThat(stored.region()).hasSize(128);
            assertThat(stored.asn()).hasSize(128);
        }
    }

    @SqlContractTest
    void theOldestLookupsAreDroppedFirstInBoundedBatches(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqIpLookupRepository lookups = repository(database);
            for (int index = 0; index < 5; index++) {
                lookups.save(new IpLookup("203.0.113." + (10 + index), IpLookupProvider.IPHUB,
                    new IpFacts(null, null, null, VpnStatus.NO, false), NOW.plusSeconds(index)));
            }

            assertThat(lookups.deleteOldest(2)).isEqualTo(2);

            assertThat(lookups.count()).isEqualTo(3);
            assertThat(lookups.find("203.0.113.10")).isEmpty();
            assertThat(lookups.find("203.0.113.11")).isEmpty();
            assertThat(lookups.find("203.0.113.12")).isPresent();
        }
    }

    @SqlContractTest
    void lookupsOlderThanACutoffAreDroppedInBoundedBatches(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqIpLookupRepository lookups = repository(database);
            for (int index = 0; index < 4; index++) {
                lookups.save(new IpLookup("203.0.113." + (20 + index), IpLookupProvider.IPHUB,
                    new IpFacts(null, null, null, VpnStatus.UNKNOWN, false), NOW.plus(Duration.ofDays(index))));
            }

            assertThat(lookups.deleteLookedUpBefore(NOW.plus(Duration.ofDays(3)), 2)).isEqualTo(2);
            assertThat(lookups.deleteLookedUpBefore(NOW.plus(Duration.ofDays(3)), 2)).isEqualTo(1);

            assertThat(lookups.count()).isEqualTo(1);
            assertThat(lookups.find("203.0.113.23")).isPresent();
        }
    }

    private static JooqIpLookupRepository repository(SqlTestDatabase database) {
        return new JooqIpLookupRepository(database.dsl(), database.unitOfWork(), database.upserts());
    }
}
