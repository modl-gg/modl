package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.update.CommittedUpdate;
import gg.modl.update.Selection.Target;
import gg.modl.update.UpdatePhase;
import gg.modl.update.UpdateTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static gg.modl.minecraft.core.embedded.EmbeddedReleases.MINOR;
import static gg.modl.minecraft.core.embedded.EmbeddedReleases.NEXT_PATCH;
import static gg.modl.minecraft.core.embedded.EmbeddedReleases.PATCH;
import static gg.modl.minecraft.core.embedded.EmbeddedReleases.PLUGIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedPayloadsTest {
    private static final Instant COMMITTED_AT = Instant.parse("2026-12-02T12:00:00Z");

    @TempDir
    Path temp;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private EmbeddedReleases releases;
    private ManagedPayloads payloads;

    @BeforeEach
    void createReleases() throws Exception {
        releases = new EmbeddedReleases(temp.resolve("work"), EmbeddedServerPaths.forPluginDataFolder(temp.resolve("modl")), logger);
        payloads = releases.payloads();
    }

    @Test
    void withoutUpdateFilesTheBasePayloadRuns() throws Exception {
        SelectedPayload selected = payloads.select(ManagedPayloadsTest::neverCancelled);

        assertEquals(Target.BASE, selected.target());
        assertEquals(PLUGIN.toString(), selected.payload().version());
    }

    @Test
    void aStagedPatchRunsFromItsArchiveCountsTheAttemptAndKeepsTheOldLibraries() throws Exception {
        releases.publish(PATCH);
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());

        SelectedPayload selected = payloads.select(ManagedPayloadsTest::neverCancelled);

        assertTrue(selected.attemptsUpdate());
        assertEquals(PATCH.toString(), selected.payload().version());
        assertEquals(releases.baseLibraries(), selected.retainedLibraries());
        assertEquals(1, releases.files().readPending().attempts());
    }

    @Test
    void aStagedPatchRunsWithoutDownloadingTheBaseArchiveWhenItIsGone() throws Exception {
        releases.publish(PATCH);
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());
        ManagedPayloads withoutBaseArchive = ManagedPayloads.managed(new BasePayload() {
            @Override
            public VerifiedArchive open(BooleanSupplier cancelled) {
                throw new AssertionError("the base archive must not be downloaded for an update attempt");
            }

            @Override
            public VerifiedArchive openInstalled() throws IOException {
                throw new IOException("modl-embedded-3.0.0.zip is missing");
            }
        }, releases.archives(), PLUGIN, EmbeddedServerPaths.forPluginDataFolder(temp.resolve("modl")).getDataDirectory(),
                releases.keys(), logger);

        try (SelectedPayload selected = withoutBaseArchive.select(ManagedPayloadsTest::neverCancelled)) {
            assertTrue(selected.attemptsUpdate());
            assertTrue(selected.retainedLibraries().isEmpty());
        }
    }

    @Test
    void aTamperedStagedArchiveIsNeverRunAndRollsBackWithoutABackup() throws Exception {
        Path zip = releases.publish(PATCH);
        Files.write(zip, new byte[] {1, 2, 3});
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());

        SelectedPayload selected = payloads.select(ManagedPayloadsTest::neverCancelled);

        UpdateTransaction pending = releases.files().readPending();
        assertEquals(Target.ROLLBACK, selected.target());
        assertEquals(PLUGIN.toString(), selected.payload().version());
        assertEquals(UpdatePhase.ROLLBACK, pending.phase());
        assertEquals(false, pending.schemaChanged());
        assertTrue(pending.rollbackAllowed());
    }

    @Test
    void anArchiveSignedWithAnUnknownKeyIsRefused() throws Exception {
        releases.publish(PATCH);
        EmbeddedReleases stranger = new EmbeddedReleases(temp.resolve("other"),
                EmbeddedServerPaths.forPluginDataFolder(temp.resolve("modl")), logger);

        IOException refused = assertThrows(IOException.class, () -> stranger.payloads().verifiedArchive(PATCH));

        assertTrue(refused.getMessage().contains("does not verify"), refused.getMessage());
    }

    @Test
    void anArchiveThatContainsAnotherVersionIsRefused() throws Exception {
        releases.publish(PATCH, NEXT_PATCH);

        IOException refused = assertThrows(IOException.class, () -> payloads.verifiedArchive(PATCH));

        assertTrue(refused.getMessage().contains("contains modl 3.0.2"), refused.getMessage());
    }

    @Test
    void aStagedPayloadNeverCrossesThePluginsLine() throws Exception {
        releases.publish(MINOR);
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, MINOR).build());

        assertEquals(Target.BASE, payloads.select(ManagedPayloadsTest::neverCancelled).target());
    }

    @Test
    void aCommittedPatchKeepsRunningOnTheNextStart() throws Exception {
        releases.publish(PATCH);
        commit();

        SelectedPayload selected = payloads.select(ManagedPayloadsTest::neverCancelled);

        assertEquals(Target.COMMITTED, selected.target());
        assertEquals(PATCH.toString(), selected.payload().version());
    }

    @Test
    void aDamagedCommittedPatchFailsClosedAndNamesTheWayOut() throws Exception {
        Path zip = releases.publish(PATCH);
        commit();
        Files.write(zip, new byte[] {1});

        EmbeddedServerException failure = assertThrows(EmbeddedServerException.class, () -> payloads.select(ManagedPayloadsTest::neverCancelled));

        assertTrue(failure.getMessage().contains("Replace the modl plugin jar with modl 3.0.1 or newer"), failure.getMessage());
    }

    @Test
    void aNewerPluginJarWinsOverACommittedPayload() throws Exception {
        releases.publish(PATCH);
        commit();
        ManagedPayloads replacedJar = ManagedPayloads.managed(releases.base(), releases.archives(), NEXT_PATCH,
                EmbeddedServerPaths.forPluginDataFolder(temp.resolve("modl")).getDataDirectory(), releases.keys(), logger);

        assertEquals(Target.BASE, replacedJar.select(ManagedPayloadsTest::neverCancelled).target());
    }

    @Test
    void aFailedUpdateReturnsToTheArtifactItCameFrom() throws Exception {
        releases.publish(PATCH);
        releases.publish(NEXT_PATCH);
        releases.files().writePending(EmbeddedReleases.update(PATCH, NEXT_PATCH).build());
        SelectedPayload failed = payloads.select(ManagedPayloadsTest::neverCancelled);

        SelectedPayload previous = payloads.rollBack(failed, "boom", ManagedPayloadsTest::neverCancelled).get();

        assertEquals(Target.ROLLBACK, previous.target());
        assertEquals(PATCH.toString(), previous.payload().version());
        assertEquals("boom", releases.files().readPending().failure());
    }

    @Test
    void aMigrationWithoutABackupIsNeverRolledBack() throws Exception {
        releases.publish(PATCH);
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).phase(UpdatePhase.MIGRATING).schemaChanged(true).build());
        SelectedPayload failed = payloads.select(ManagedPayloadsTest::neverCancelled);

        assertFalse(payloads.rollBack(failed, "boom", ManagedPayloadsTest::neverCancelled).isPresent());
        assertEquals(UpdatePhase.MIGRATING, releases.files().readPending().phase());
    }

    @Test
    void aCommittedTransactionIsNeverRolledBack() throws Exception {
        releases.publish(PATCH);
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());
        SelectedPayload started = payloads.select(ManagedPayloadsTest::neverCancelled);
        commit();

        assertFalse(payloads.rollBack(started, "late failure", ManagedPayloadsTest::neverCancelled).isPresent());
        assertTrue(payloads.committed(started));
        assertEquals(Target.COMMITTED, payloads.select(ManagedPayloadsTest::neverCancelled).target());
        assertNull(releases.files().readPending());
    }

    @Test
    void aStagedArchiveReplacedAfterItsHashIsNeverRead() throws Exception {
        Path zip = releases.publish(PATCH);
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());

        try (SelectedPayload selected = payloads.select(ManagedPayloadsTest::neverCancelled)) {
            Files.write(zip, new byte[] {1, 2, 3});

            assertEquals(PATCH.toString(), selected.payload().version());
            assertFalse(selected.payload().libraryFileNames().isEmpty());
        }
    }

    @Test
    void theVerifiedCopyIsRemovedOnceTheSelectionIsClosed() throws Exception {
        releases.publish(PATCH);
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());

        payloads.select(ManagedPayloadsTest::neverCancelled).close();

        try (Stream<Path> copies = Files.list(releases.files().runDirectory())) {
            assertFalse(copies.findAny().isPresent());
        }
    }

    @Test
    void aStalledAttemptRollsBackOnTheNextStartInsteadOfTryingAgain() throws Exception {
        releases.publish(PATCH);
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());
        payloads.select(ManagedPayloadsTest::neverCancelled).close();

        payloads.abandonStalledAttempt("The built-in panel made no progress for 900 seconds while it was updating.");

        try (SelectedPayload next = payloads.select(ManagedPayloadsTest::neverCancelled)) {
            assertEquals(Target.ROLLBACK, next.target());
            assertEquals(PLUGIN.toString(), next.payload().version());
        }
        assertEquals(UpdatePhase.ROLLBACK, releases.files().readPending().phase());
        assertTrue(releases.files().readPending().failure().contains("no progress"));
    }

    @Test
    void aStallAfterTheCommitChangesNothing() throws Exception {
        releases.publish(PATCH);
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());
        payloads.select(ManagedPayloadsTest::neverCancelled).close();
        commit();

        payloads.abandonStalledAttempt("late stall");

        assertEquals(Target.COMMITTED, payloads.select(ManagedPayloadsTest::neverCancelled).target());
    }

    @Test
    void theLibrariesToKeepAreNeverReadFromAnUnverifiedPreviousArchive() throws Exception {
        Path previous = releases.publish(PATCH);
        Path next = releases.publish(NEXT_PATCH);
        Files.copy(next, previous, StandardCopyOption.REPLACE_EXISTING);
        releases.files().writePending(EmbeddedReleases.update(PATCH, NEXT_PATCH).build());

        try (SelectedPayload selected = payloads.select(ManagedPayloadsTest::neverCancelled)) {
            assertTrue(selected.attemptsUpdate());
            assertTrue(selected.retainedLibraries().isEmpty(), selected.retainedLibraries().toString());
        }
    }

    private void commit() throws IOException {
        releases.files().writeCommitted(CommittedUpdate.of(EmbeddedReleases.update(PLUGIN, PATCH).build(), COMMITTED_AT));
    }

    private static boolean neverCancelled() {
        return false;
    }
}
