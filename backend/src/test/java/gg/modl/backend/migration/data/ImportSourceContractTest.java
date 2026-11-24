package gg.modl.backend.migration.data;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.proto.fixtures.PluginImportSources;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportSourceContractTest {

    @Test
    void theBackendKnowsExactlyThePinnedSourcesWithTheirNames() {
        Map<String, String> known = new LinkedHashMap<>();
        Arrays.stream(ImportSource.values()).forEach(source -> known.put(source.id(), source.displayName()));

        assertThat(known).containsExactlyEntriesOf(PluginImportSources.displayNamesById());
    }

    @Test
    void anUnknownSourceIsNamedByItsId() {
        assertThat(ImportSource.displayNameOf("maxbans")).isEqualTo("maxbans");
        assertThat(ImportSource.displayNameOf("banmanager")).isEqualTo("BanManager");
    }
}
