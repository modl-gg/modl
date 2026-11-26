package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.response.ImportJob;
import gg.modl.minecraft.api.http.response.ImportStatusResponse;
import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.Java8Collections;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportReporterTest {
    private static final Pattern UNFILLED = Pattern.compile("\\{[a-z_]+}");
    private static final Pattern RAW_KEY = Pattern.compile("import\\.[a-z_]+");

    private static final Map<String, String> CHOICES = Java8Collections.mapOf("scoped_choice", "widen", "history_choice", "keep");

    private final List<String> console = new ArrayList<>();
    private final ImportSource source = new NamedSource();
    private final ImportReporter reporter = new ImportReporter(new LocaleManager(), console::add,
            new ImportSources(Collections.singletonList(source)));

    @Test
    void thePreviewFillsEveryPlaceholder() {
        SourceInventory inventory = SourceInventory.builder().players(3).bans(2).mutes(1).warnings(1).kicks(1).notes(1)
                .ipWide(1).serverScoped(1).withoutAccount(1).build();
        ImportStatusResponse status = new ImportStatusResponse(null, true, 1024, 365, 1L);

        reporter.preview(source, inventory, 7, status);

        assertEverythingFilled();
        assertTrue(console.get(0).contains("Example has 2 bans, 1 mutes, 1 warnings, 1 kicks, and 1 notes for 3 players."),
                console.get(0));
    }

    @Test
    void resultsStatusAndRefusalsFillEveryPlaceholder() {
        ImportJob job = ImportJob.builder().id("job").type("example").status("completed").startedFrom("proxy")
                .startedAddress("127.0.0.1").scopedPunishments("SCOPED_PUNISHMENT_CHOICE_WIDEN")
                .ipHistory("IMPORTED_IP_HISTORY_CHOICE_KEEP").recordsProcessed(3).recordsSkipped(0).totalRecords(3)
                .exportFailed(1).exportSkipped(2).build();

        reporter.result(job);
        reporter.status(new ImportStatusResponse(job, true, 1024, 365, null));
        reporter.unknownSource("maxbans");
        reporter.supported(Collections.singletonList("example"));
        reporter.supported(Collections.emptyList());
        for (String key : new String[]{"import.source_missing", "import.source_locked", "import.nothing_to_import",
                "import.confirm_first", "import.choices_required", "import.started"}) {
            reporter.say(source, key, CHOICES);
        }
        reporter.failed(source, "import.database_failed", "broken");

        assertEverythingFilled();
        assertTrue(console.stream().anyMatch(line -> line.startsWith("Example import completed with problems")), console.toString());
    }

    @Test
    void theStartingAddressIsShownInItsShortForm() {
        ImportJob job = ImportJob.builder().id("job").type("example").status("processing_data").startedFrom("proxy")
                .startedAddress("0:0:0:0:0:0:0:1").build();

        reporter.status(new ImportStatusResponse(job, true, 1024, 365, null));

        assertTrue(console.stream().anyMatch(line -> line.contains("(::1)")), console.toString());
        assertTrue(console.stream().noneMatch(line -> line.contains("0:0:0:0:0:0:0:1")), console.toString());
    }

    private void assertEverythingFilled() {
        for (String line : console) {
            assertTrue(!UNFILLED.matcher(line).find(), line);
            assertTrue(!RAW_KEY.matcher(line).find(), line);
        }
    }

    private static final class NamedSource implements ImportSource {
        @Override
        public String id() {
            return "example";
        }

        @Override
        public String displayName() {
            return "Example";
        }

        @Override
        public boolean isPresent() {
            return true;
        }

        @Override
        public SourceReader open() {
            throw new UnsupportedOperationException();
        }
    }
}
