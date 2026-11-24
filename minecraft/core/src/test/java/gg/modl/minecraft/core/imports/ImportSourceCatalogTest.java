package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.sql.SourceFixtures;
import gg.modl.proto.fixtures.PluginImportSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportSourceCatalogTest {
    @TempDir
    Path root;

    @Test
    void thePluginOffersExactlyThePinnedSourcesWithTheirNames() {
        Map<String, String> offered = new LinkedHashMap<>();
        for (ImportSource source : ImportSourceCatalog.sources(SourceFixtures.context(root))) {
            offered.put(source.id(), source.displayName());
        }

        assertEquals(PluginImportSources.displayNamesById(), offered);
    }
}
