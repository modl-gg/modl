package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.request.StartImportJobRequest;
import gg.modl.minecraft.core.impl.commands.staff.ModlImportCommand;
import gg.modl.minecraft.core.imports.litebans.LiteBansSource;
import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportsTest {
    private static final String PLAYER = "11111111-1111-4111-8111-111111111111";
    private static final long WAIT_MILLIS = 10_000;

    @TempDir
    Path dataFolder;

    private final FakeLiteBansDatabase database = new FakeLiteBansDatabase();
    private final FakeImportBackend backend = new FakeImportBackend();
    private final List<String> console = new CopyOnWriteArrayList<>();
    private volatile boolean enabled = true;
    private volatile boolean otherPresent;
    private Imports imports;

    @BeforeEach
    void createImports() {
        database.punished(FakeLiteBansDatabase.BANS, 1, PLAYER, System.currentTimeMillis(), 0);
        imports = imports(() -> database);
    }

    private Imports imports(LiteBansSource.ProviderFactory providers) {
        return Imports.builder()
                .sources(new ImportSources(Arrays.asList(
                        new LiteBansSource(() -> enabled, providers, () -> "No reason", System::currentTimeMillis),
                        new AbsentSource())))
                .backend(backend)
                .localeManager(new MapLocaleManager().withFallback(path -> path))
                .console(console::add)
                .logger(new RecordingPluginLogger())
                .dataFolder(dataFolder)
                .serverName(() -> "proxy")
                .build();
    }

    @AfterEach
    void stopImports() {
        imports.shutdown();
    }

    @Test
    void withoutLiteBansNothingStarts() {
        enabled = false;

        imports.preview("litebans");

        awaitQuietly(() -> console.contains("import.litebans_missing"));
        assertEquals(1, console.stream().filter("import.litebans_missing"::equals).count());
        assertTrue(backend.starts().isEmpty());
    }

    @Test
    void aStartNeedsAPreviewFirst() {
        imports.confirm("litebans", "skip", "keep");

        assertEquals("import.confirm_first", console.get(0));
        assertTrue(backend.starts().isEmpty());
    }

    @Test
    void bothChoicesMustBeNamed() {
        imports.confirm("litebans", null, "keep");
        imports.confirm("litebans", "widen", null);
        imports.confirm("litebans", "keep", "widen");

        assertEquals(3, console.stream().filter("import.choices_required"::equals).count());
        assertTrue(backend.starts().isEmpty());
    }

    @Test
    void aConfirmedImportStartsWithBothChoicesUploadsAndReportsTheResult() throws Exception {
        imports.preview("LiteBans");
        await(() -> console.contains("import.preview.confirm"));

        imports.confirm("litebans", "SKIP", "keep");
        await(() -> !backend.starts().isEmpty());
        ImportSourceConnectionTest.assertRightAfterThePreview(console);
        await(() -> console.contains("import.completed"));

        StartImportJobRequest start = backend.starts().get(0);
        assertEquals("litebans", start.getType());
        assertEquals("proxy", start.getServerName());
        assertEquals(ScopedPunishmentChoice.SKIP.getWireName(), start.getScopedPunishments());
        assertEquals(IpHistoryChoice.KEEP.getWireName(), start.getIpHistory());
        assertEquals(1, backend.starts().size());
        assertEquals(1, backend.uploads().size());
        await(() -> database.closes() == 1);
    }

    @Test
    void aStartRefusedWhileOldIpHistoryIsReleasedSaysSoAndUploadsNothing() throws Exception {
        assertRefusedStartSays("IP_HISTORY_RELEASE_RUNNING", "import.ip_history_release_running");
    }

    @Test
    void aStartRefusedWhileUploadedFilesMoveToAnotherStorageSaysSoAndUploadsNothing() throws Exception {
        assertRefusedStartSays("IMPORT_STORAGE_MOVING", "import.storage_moving");
    }

    private void assertRefusedStartSays(String code, String message) throws Exception {
        backend.refusingStartsWith(code);
        imports.preview("litebans");
        await(() -> console.contains("import.preview.confirm"));

        imports.confirm("litebans", "skip", "standard");
        await(() -> !backend.starts().isEmpty());
        ImportSourceConnectionTest.assertRightAfterThePreview(console);
        await(() -> console.contains(message));

        assertTrue(backend.uploads().isEmpty());
        assertTrue(console.stream().noneMatch("import.request_failed"::equals));
    }

    @Test
    void anEmptyDatabaseNeverReachesTheBackend() throws Exception {
        imports.shutdown();
        imports = imports(FakeLiteBansDatabase::new);

        imports.preview("litebans");

        await(() -> console.contains("import.nothing_to_import"));
        assertTrue(backend.starts().isEmpty());
    }

    @Test
    void theCommandRoutesEachSubcommand() throws Exception {
        enabled = false;
        Lamp<CommandActor> lamp = Lamp.<CommandActor>builder().build();
        lamp.register(new ModlImportCommand(imports));
        FakeCommandActor actor = new FakeCommandActor(null, "CONSOLE");

        lamp.dispatch(actor, "modl import");
        lamp.dispatch(actor, "modl import maxbans");
        lamp.dispatch(actor, "modl import litebans confirm");
        lamp.dispatch(actor, "modl import litebans confirm widen standard");
        lamp.dispatch(actor, "modl import cancel");

        await(() -> console.contains("import.nothing_detected"));
        assertEquals("import.usage", console.get(0));
        List<String> answers = console.stream().filter(line -> !"import.nothing_detected".equals(line)).collect(Collectors.toList());
        assertEquals(Arrays.asList("import.usage", "import.unknown_source", "import.choices_required", "import.confirm_first",
                "import.nothing_to_cancel"), answers);
        assertTrue(backend.starts().isEmpty());
    }

    @Test
    void onlyDetectedSourcesAreOfferedAndTheListIsRefreshedInTheBackground() throws Exception {
        assertTrue(imports.detectedSources().isEmpty());
        imports.resumeInterrupted();
        await(() -> Collections.singletonList("litebans").equals(imports.detectedSources()));

        otherPresent = true;
        enabled = false;
        imports.usage();

        await(() -> Collections.singletonList("absent").equals(imports.detectedSources()));
        await(() -> console.contains("import.detected"));
    }

    @Test
    void aMissingSourceSaysSoWithItsName() throws Exception {
        imports.preview("absent");

        await(() -> console.contains("import.source_missing"));
        assertTrue(backend.starts().isEmpty());
    }

    private static void awaitQuietly(BooleanSupplier condition) {
        try {
            await(condition);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private final class AbsentSource implements ImportSource {
        @Override
        public String id() {
            return "absent";
        }

        @Override
        public String displayName() {
            return "Absent";
        }

        @Override
        public boolean isPresent() {
            return otherPresent;
        }

        @Override
        public SourceReader open() throws SourceException {
            throw SourceException.missing("Nothing here");
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }
}
