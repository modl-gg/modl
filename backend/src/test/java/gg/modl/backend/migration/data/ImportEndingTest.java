package gg.modl.backend.migration.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ImportEndingTest {
    @Test
    void aPluginCanOnlyEndAnAcceptedImportByCancellingIt() {
        assertThat(Arrays.stream(ImportEnding.values())
            .filter(ImportEnding::mayBeReportedByPlugin)
            .filter(ImportEnding::endsAcceptedImport))
            .containsExactly(ImportEnding.CANCELLED);
    }

    @Test
    void theServerCanEndAnAcceptedImportForItsOwnReasons() {
        assertThat(ImportEnding.TIMED_OUT.endsAcceptedImport()).isTrue();
        assertThat(ImportEnding.INVALID_FILE.endsAcceptedImport()).isTrue();
        assertThat(ImportEnding.IMPORT_FAILED.endsAcceptedImport()).isTrue();
    }
}
