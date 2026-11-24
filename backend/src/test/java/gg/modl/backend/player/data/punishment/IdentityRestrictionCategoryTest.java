package gg.modl.backend.player.data.punishment;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IdentityRestrictionCategoryTest {
    private static final Map<Integer, PunishmentType> DEFAULTS = PunishmentTypeIndex.byOrdinal(DefaultPunishmentTypes.getAll());

    @ParameterizedTest
    @CsvSource({
        "10, blockedName, BadName, BAN",
        "11, blockedSkin, 0a1b2c, BAN",
        "11, blockedSkin, default, BAN",
        "10, blockedName, Unknown, NONE",
        "10, blockedName, '', NONE",
        "10, blockedSkin, 0a1b2c, NONE",
        "11, blockedName, BadName, NONE"
    })
    void aRestrictionBansOnlyWhenSomethingKnownCanLiftIt(int ordinal, String key, String value, ResolvedCategory expected) {
        PunishmentDataView data = PunishmentDataView.ofMap(new HashMap<>(Map.of(key, value)));

        assertThat(EnforcementCategoryResolver.resolve(DEFAULTS.get(ordinal), data)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"10", "11"})
    void anOlderRecordWithoutALiftValueNeverBecomesABan(int ordinal) {
        PunishmentDataView data = PunishmentDataView.ofMap(new HashMap<>(Map.of("severity", "regular")));

        assertThat(EnforcementCategoryResolver.resolve(DEFAULTS.get(ordinal), data)).isEqualTo(ResolvedCategory.NONE);
    }
}
