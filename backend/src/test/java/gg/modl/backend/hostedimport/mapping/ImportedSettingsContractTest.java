package gg.modl.backend.hostedimport.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportedSettingsContractTest {
    private static final String FIXTURE = "gg/modl/proto/fixtures/hosted-import-settings.txt";

    @Test
    void thePanelNamesEveryImportedSettingTheBackendCanReportAsUnreadable() throws IOException {
        try (InputStream input = ImportedSettingsContractTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(input).isNotNull();
            List<String> pinned = Arrays.stream(new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                .filter(line -> !line.isEmpty())
                .toList();
            assertThat(SettingsImport.IMPORTED).containsExactlyInAnyOrderElementsOf(pinned);
        }
    }
}
