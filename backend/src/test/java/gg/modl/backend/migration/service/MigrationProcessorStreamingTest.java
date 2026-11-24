package gg.modl.backend.migration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportedCounts;
import gg.modl.backend.migration.validation.MigrationValidator;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.repository.ImportBatchResult;
import gg.modl.backend.player.repository.ImportedAddressRetention;
import gg.modl.backend.player.repository.PlayerImportRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class MigrationProcessorStreamingTest {

    private static final String JOB_ID = "job-1";
    private static final String FIRST_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String SECOND_UUID = "66666666-7777-8888-9999-000000000000";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final PlayerImportRepository importRepository = mock(PlayerImportRepository.class);
    private final ImportProcessingJobs processingJobs = mock(ImportProcessingJobs.class);
    private final MigrationRecordMapper recordMapper = mock(MigrationRecordMapper.class);
    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final PunishmentTypeService punishmentTypeService = mock(PunishmentTypeService.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final MigrationProcessor processor = new MigrationProcessor(
        importRepository, processingJobs, new MigrationValidator(), recordMapper, punishmentTypeService, clock);

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        when(processingJobs.addressRetention(JOB_ID)).thenReturn(ImportedAddressRetention.KEPT);
        when(recordMapper.toPlayer(anyString(), anyMap(), anyMap(), eq(NOW)))
            .thenAnswer(invocation -> Player.builder().minecraftUuid(UUID.fromString(invocation.getArgument(0))).build());
        when(importRepository.importBatch(anyList(), eq(NOW), eq(ImportedAddressRetention.KEPT))).thenAnswer(invocation -> {
            List<Player> players = invocation.getArgument(0);
            return new ImportBatchResult(players.size(), 0, 0);
        });
    }

    @Test
    void streamsEveryPlayerRecordAndCompletesItsOwnJob() throws Exception {
        Path file = directory.resolve("migration.json");
        Files.writeString(file, """
            {
              "metadata": {"playerCount": 3, "source": "test"},
              "players": [
                {"minecraftUuid": "%s", "usernames": [{"username": "First"}]},
                {"minecraftUuid": "%s", "notes": []},
                {"minecraftUuid": "not-a-uuid"}
              ],
              "trailingSection": {"ignored": true}
            }
            """.formatted(FIRST_UUID, SECOND_UUID));
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true);
        when(processingJobs.hasEnded(JOB_ID)).thenReturn(true);

        processor.processFile(server, JOB_ID, file);

        verify(processingJobs).complete(server, JOB_ID, new ImportedCounts(2, 1, 3));

        ArgumentCaptor<List<Player>> imported = ArgumentCaptor.captor();
        verify(importRepository).importBatch(imported.capture(), eq(NOW), eq(ImportedAddressRetention.KEPT));
        assertEquals(List.of(FIRST_UUID, SECOND_UUID),
            imported.getValue().stream().map(player -> player.getMinecraftUuid().toString()).toList());
        assertFalse(Files.exists(file));
    }

    @Test
    void aWorkerWhoseJobEndedBeforeItStartedWritesNothing() throws Exception {
        Path file = players(1);
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(false);
        when(processingJobs.hasEnded(JOB_ID)).thenReturn(true);

        processor.processFile(server, JOB_ID, file);

        verifyNoInteractions(importRepository);
        verify(processingJobs, never()).reportProgress(anyString(), any(ImportedCounts.class));
        assertFalse(Files.exists(file));
    }

    @Test
    void aWorkerWhoseJobEndsMidwayStopsAfterTheBatchAndReportsNothingMore() throws Exception {
        Path file = players(1_200);
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true, true, false);

        processor.processFile(server, JOB_ID, file);

        ArgumentCaptor<List<Player>> imported = ArgumentCaptor.captor();
        verify(importRepository).importBatch(imported.capture(), eq(NOW), eq(ImportedAddressRetention.KEPT));
        assertEquals(500, imported.getValue().size());
        assertEquals(2, reported(2).size());
        verify(processingJobs, never()).complete(any(), anyString(), any());
    }

    @Test
    void aBatchWithManyRowsIsCommittedInUnitsOfAboutFiveHundred() throws Exception {
        Path file = players(3);
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true);
        everyPlayerHasThreeHundredPunishments();

        processor.processFile(server, JOB_ID, file);

        verify(importRepository, times(3)).importBatch(anyList(), eq(NOW), eq(ImportedAddressRetention.KEPT));
    }

    @Test
    void aLongStretchOfSkippedEntriesKeepsReportingProgress() throws Exception {
        Path file = entries(Collections.nCopies(2_500, "0"));
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true);

        processor.processFile(server, JOB_ID, file);

        assertEquals(List.of(0, 0, 1_000, 2_000), reported(4).stream().map(ImportedCounts::skipped).toList());
        verify(processingJobs).complete(server, JOB_ID, new ImportedCounts(0, 2_500, null));
        verifyNoInteractions(importRepository);
    }

    @Test
    void recordsWithAnUnreadableUuidCountTowardsTheNextReport() throws Exception {
        Path file = entries(Collections.nCopies(1_000, "{\"minecraftUuid\":\"not-a-uuid\"}"));
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true);

        processor.processFile(server, JOB_ID, file);

        assertEquals(List.of(0, 0, 1_000), reported(3).stream().map(ImportedCounts::skipped).toList());
    }

    @Test
    void processedAndSkippedRecordsTogetherTriggerAReport() throws Exception {
        List<String> mixed = new ArrayList<>(records(500));
        mixed.addAll(Collections.nCopies(500, "0"));
        Path file = entries(mixed);
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true);

        processor.processFile(server, JOB_ID, file);

        assertEquals(new ImportedCounts(500, 500, null), reported(3).get(2));
    }

    @Test
    void slowUnitsReportAtLeastOnceAMinute() throws Exception {
        Path file = players(3);
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true);
        everyPlayerHasThreeHundredPunishments();
        when(importRepository.importBatch(anyList(), eq(NOW), eq(ImportedAddressRetention.KEPT))).thenAnswer(invocation -> {
            clock.advance(Duration.ofMinutes(1));
            return new ImportBatchResult(1, 0, 0);
        });

        processor.processFile(server, JOB_ID, file);

        assertEquals(List.of(0, 0, 1, 2, 3), reported(5).stream().map(ImportedCounts::processed).toList());
    }

    @Test
    void aWorkerWhoseJobEndedDuringSkippedEntriesStopsWithoutReporting() throws Exception {
        Path file = entries(Collections.nCopies(2_500, "0"));
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true, true, false);

        processor.processFile(server, JOB_ID, file);

        assertEquals(List.of(0, 0), reported(2).stream().map(ImportedCounts::skipped).toList());
    }

    @Test
    void anUnreadableFileFailsAsInvalidWithItsReason() throws Exception {
        Path file = Files.writeString(directory.resolve("broken.json"), "{\"players\":[{\"minecraftUuid\":");
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true);

        processor.processFile(server, JOB_ID, file);

        verify(processingJobs).fail(eq(JOB_ID), eq(ImportEnding.INVALID_FILE), anyString(), any(ImportedCounts.class));
    }

    @Test
    void anOversizedRecordFailsAsInvalidWithoutBeingImported() throws Exception {
        String values = "0,".repeat((int) RecordBoundedInputStream.MAX_RECORD_VALUES + 100_000) + "0";
        Path file = entries(List.of("{\"minecraftUuid\":\"" + FIRST_UUID + "\",\"notes\":[" + values + "]}"));
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true);

        processor.processFile(server, JOB_ID, file);

        verify(processingJobs).fail(eq(JOB_ID), eq(ImportEnding.INVALID_FILE),
            eq("A player record in the import file is too large"), any(ImportedCounts.class));
        verifyNoInteractions(importRepository);
    }

    @Test
    void aBatchIsFlushedBySizeBeforeItHasFiveHundredPlayers() throws Exception {
        String padding = "x".repeat(900_000);
        Path file = entries(records(40).stream()
            .map(record -> record.replace("}", ",\"padding\":\"" + padding + "\"}"))
            .toList());
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true);

        processor.processFile(server, JOB_ID, file);

        verify(importRepository, times(3)).importBatch(anyList(), eq(NOW), eq(ImportedAddressRetention.KEPT));
    }

    @Test
    void aFailedDatabaseWriteStoresTheReason() throws Exception {
        Path file = players(1);
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true);
        when(importRepository.importBatch(anyList(), eq(NOW), eq(ImportedAddressRetention.KEPT))).thenThrow(new IllegalStateException("write refused"));

        processor.processFile(server, JOB_ID, file);

        verify(processingJobs).fail(eq(JOB_ID), eq(ImportEnding.IMPORT_FAILED), eq("Migration failed: write refused"),
            any(ImportedCounts.class));
    }

    @Test
    void aShutdownKeepsTheFileForTheNextBoot() throws Exception {
        Path file = players(1_200);
        when(processingJobs.shouldContinue(JOB_ID)).thenReturn(true, true, false);
        when(processingJobs.hasEnded(JOB_ID)).thenReturn(false);

        processor.processFile(server, JOB_ID, file);

        verify(processingJobs, never()).fail(anyString(), any(), anyString(), any());
        assertTrue(Files.exists(file));
    }

    private void everyPlayerHasThreeHundredPunishments() {
        doAnswer(invocation -> Player.builder()
            .minecraftUuid(UUID.fromString(invocation.getArgument(0)))
            .punishments(IntStream.range(0, 300)
                .mapToObj(index -> new Punishment())
                .collect(Collectors.toCollection(ArrayList::new)))
            .build())
            .when(recordMapper).toPlayer(anyString(), anyMap(), anyMap(), eq(NOW));
    }

    private List<ImportedCounts> reported(int times) {
        ArgumentCaptor<ImportedCounts> progress = ArgumentCaptor.forClass(ImportedCounts.class);
        verify(processingJobs, times(times)).reportProgress(eq(JOB_ID), progress.capture());
        return progress.getAllValues();
    }

    private Path players(int count) throws Exception {
        return entries(records(count));
    }

    private static List<String> records(int count) {
        return IntStream.range(0, count)
            .mapToObj(index -> "{\"minecraftUuid\":\"" + new UUID(0x1234_4000L, 0x8000_0000_0000_0000L + index) + "\"}")
            .toList();
    }

    private Path entries(List<String> entries) throws Exception {
        return Files.writeString(directory.resolve("players.json"), "{\"players\":[" + String.join(",", entries) + "]}");
    }
}
