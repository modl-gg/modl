package gg.modl.backend.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunningImportsTest {
    @Test
    void anImporterLooksOnlyAtTheOtherImporters() {
        RunningImports imports = TestImports.running(() -> List.of(TestImports.of(ImportKind.PLUGIN, () -> true),
            TestImports.of(ImportKind.HOSTED, () -> false)));

        assertThat(imports.any()).isTrue();
        assertThat(imports.anyBesides(ImportKind.PLUGIN)).isFalse();
        assertThat(imports.anyBesides(ImportKind.HOSTED)).isTrue();
    }

    @Test
    void nothingRunsWithoutImporters() {
        assertThat(TestImports.none().any()).isFalse();
    }
}
