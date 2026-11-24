package gg.modl.backend.migration.service;

import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportedCounts;
import gg.modl.backend.migration.validation.MigrationValidator;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.repository.ImportBatchResult;
import gg.modl.backend.player.repository.ImportedAddressRetention;
import gg.modl.backend.player.repository.PlayerImportRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationProcessor {
    private final PlayerImportRepository playerImportRepository;
    private final ImportProcessingJobs processingJobs;
    private final MigrationValidator validator;
    private final MigrationRecordMapper recordMapper;
    private final PunishmentTypeService punishmentTypeService;
    private final Clock clock;

    private static final int BATCH_SIZE = 500;
    private static final long BATCH_BYTES = RecordBoundedInputStream.MAX_RECORD_BYTES;
    private static final int PROGRESS_UPDATE_INTERVAL = 1000;
    private static final Duration PROGRESS_UPDATE_MAX_DELAY = Duration.ofMinutes(1);
    private static final int MAX_JSON_NESTING_DEPTH = 100;
    private static final int MAX_JSON_STRING_LENGTH = 1_000_000;
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 900;
    private static final String PLAYERS_FIELD = "players";
    private static final String METADATA_FIELD = "metadata";
    private static final String PLAYER_COUNT_FIELD = "playerCount";
    private static final JsonMapper MIGRATION_FILE_MAPPER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                .maxStringLength(MAX_JSON_STRING_LENGTH)
                .build())
            .build())
        .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    @Async("migrationTaskExecutor")
    public void processFileAsync(Server server, String jobId, Path filePath) {
        try {
            processFile(server, jobId, filePath);
        } catch (Exception e) {
            log.error("Async migration processing failed", e);
        }
    }

    public void processFile(Server server, String jobId, Path filePath) {
        ImportRun run = new ImportRun(server, jobId, processingJobs.addressRetention(jobId), new ProgressCounters(clock.instant()));

        try {
            if (mustStop(run)) {
                return;
            }
            announceProcessingProgress(run);

            if (streamMigrationFile(run, filePath) || mustStop(run)) {
                return;
            }

            try {
                processingJobs.complete(server, jobId, run.counters().snapshot());
            } catch (Exception e) {
                log.error("Failed to persist terminal migration state (completed)", e);
            }

        } catch (MigrationDataException e) {
            failMigration(run, ImportEnding.INVALID_FILE, e.reason());
        } catch (JacksonIOException e) {
            log.error("Error reading migration file", e);
            failMigration(run, ImportEnding.IMPORT_FAILED, boundFailureMessage("Migration failed: ", e.getMessage()));
        } catch (JacksonException e) {
            log.warn("The migration file is not valid JSON", e);
            failMigration(run, ImportEnding.INVALID_FILE, boundFailureMessage("Invalid import file: ", e.getOriginalMessage()));
        } catch (Exception e) {
            log.error("Error processing migration file", e);
            failMigration(run, ImportEnding.IMPORT_FAILED, boundFailureMessage("Migration failed: ", e.getMessage()));
        } finally {
            deleteWhenEnded(jobId, filePath);
        }
    }

    private void failMigration(ImportRun run, ImportEnding ending, String message) {
        try {
            if (mustStop(run)) {
                return;
            }
            processingJobs.fail(run.jobId(), ending, message, run.counters().snapshot());
        } catch (Exception e) {
            log.error("Failed to persist terminal migration state (failed)", e);
        }
    }

    private void deleteWhenEnded(String jobId, Path filePath) {
        if (!processingJobs.hasEnded(jobId)) {
            return;
        }
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Failed to delete migration file: {}", filePath, e);
        }
    }

    private boolean mustStop(ImportRun run) {
        if (processingJobs.shouldContinue(run.jobId())) {
            return false;
        }
        log.info("Import job {} stopped after {} records", run.jobId(), run.counters().processed());
        return true;
    }

    private boolean streamMigrationFile(ImportRun run, Path filePath) {
        try (InputStream file = Files.newInputStream(filePath);
             RecordBoundedInputStream records = new RecordBoundedInputStream(file)) {
            return streamDocument(run, records);
        } catch (IOException e) {
            throw new UncheckedIOException("The migration file could not be read", e);
        }
    }

    private boolean streamDocument(ImportRun run, RecordBoundedInputStream records) {
        ProgressCounters counters = run.counters();
        try (JsonParser parser = MIGRATION_FILE_MAPPER.createParser(records)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new MigrationDataException("Migration data must be a JSON object");
            }

            boolean playersStreamed = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();

                if (PLAYERS_FIELD.equals(field)) {
                    Optional<String> headerProblem = validator.playersHeaderProblem(
                        parser.currentToken() == JsonToken.START_ARRAY, counters.total());
                    if (headerProblem.isPresent()) {
                        throw new MigrationDataException(headerProblem.get());
                    }
                    if (mustStop(run)) {
                        return true;
                    }
                    announceProcessingProgress(run);
                    if (streamPlayers(run, parser, records)) {
                        return true;
                    }
                    playersStreamed = true;
                } else if (METADATA_FIELD.equals(field)) {
                    counters.total(readDeclaredPlayerCount(parser));
                } else {
                    parser.skipChildren();
                }
            }

            if (!playersStreamed) {
                throw new MigrationDataException(validator.missingPlayersProblem());
            }

            return false;
        }
    }

    private boolean streamPlayers(ImportRun run, JsonParser parser, RecordBoundedInputStream records) {
        ProgressCounters counters = run.counters();
        long seen = 0;
        long batchStartedAt = records.position();
        List<Map<?, ?>> batch = new ArrayList<>(BATCH_SIZE);

        while (parser.nextToken() != JsonToken.END_ARRAY) {
            seen++;
            if (seen > MigrationValidator.MAX_PLAYER_RECORDS) {
                throw new MigrationDataException("Players array exceeds maximum length of 1,000,000");
            }

            if (parser.currentToken() == JsonToken.START_OBJECT) {
                batch.add(readRecord(parser, records));
            } else {
                parser.skipChildren();
                counters.skipOne();
            }

            if (batch.size() >= BATCH_SIZE || records.position() - batchStartedAt >= BATCH_BYTES) {
                if (drainBatch(run, batch)) {
                    return true;
                }
                batchStartedAt = records.position();
            }
            if (announceWhenDue(run)) {
                return true;
            }
        }

        if (seen == 0) {
            throw new MigrationDataException("Players array cannot be empty");
        }

        return !batch.isEmpty() && drainBatch(run, batch);
    }

    private static Map<?, ?> readRecord(JsonParser parser, RecordBoundedInputStream records) {
        records.startRecord();
        try {
            return MIGRATION_FILE_MAPPER.readValue(parser, Map.class);
        } catch (JacksonException e) {
            if (records.limitExceeded()) {
                throw new MigrationDataException("A player record in the import file is too large");
            }
            throw e;
        } finally {
            records.endRecord();
        }
    }

    private boolean drainBatch(ImportRun run, List<Map<?, ?>> batch) {
        boolean ended = processBatch(run, batch);
        batch.clear();
        return ended || mustStop(run) || announceWhenDue(run);
    }

    private boolean announceWhenDue(ImportRun run) {
        if (!run.counters().dueForAnnounce(clock.instant())) {
            return false;
        }
        if (mustStop(run)) {
            return true;
        }
        announceProcessingProgress(run);
        return false;
    }

    private void announceProcessingProgress(ImportRun run) {
        ProgressCounters counters = run.counters();
        processingJobs.reportProgress(run.jobId(), counters.snapshot());
        counters.announced(clock.instant());
    }

    private @Nullable Integer readDeclaredPlayerCount(JsonParser parser) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return null;
        }
        Integer playerCount = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            if (PLAYER_COUNT_FIELD.equals(field) && parser.currentToken().isNumeric()) {
                long value = parser.getValueAsLong(-1L);
                playerCount = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
            } else {
                parser.skipChildren();
            }
        }
        return playerCount;
    }

    private static String boundFailureMessage(String prefix, @Nullable String detail) {
        return MigrationMessages.truncate(prefix + (detail == null ? "unknown error" : detail),
            MAX_FAILURE_MESSAGE_LENGTH);
    }

    private boolean processBatch(ImportRun run, List<Map<?, ?>> batch) {
        ProgressCounters counters = run.counters();
        Instant importedAt = clock.instant();
        Map<Integer, PunishmentType> typesByOrdinal =
            PunishmentTypeIndex.byOrdinal(punishmentTypeService.getPunishmentTypes(run.server()));

        List<Player> players = new ArrayList<>(batch.size());
        for (Map<?, ?> playerMap : batch) {
            if (!(playerMap.get("minecraftUuid") instanceof String rawUuid)) {
                counters.skipOne();
                continue;
            }
            String uuid = validator.normalizeUuid(rawUuid);
            if (!validator.isValidUuid(uuid)) {
                counters.skipOne();
                continue;
            }
            try {
                players.add(recordMapper.toPlayer(uuid, playerMap, typesByOrdinal, importedAt));
            } catch (IllegalArgumentException | ArithmeticException | DateTimeException e) {
                log.warn("Skipped an unreadable import record for player {}", uuid, e);
                counters.skipOne();
            }
        }

        for (List<Player> unit : ImportUnits.byRowCount(players, ImportUnits::rowCount)) {
            ImportBatchResult result = playerImportRepository.importBatch(unit, importedAt, run.retention());
            counters.addProcessed(result.created() + result.merged());
            counters.addSkipped(result.skipped());
            if (announceWhenDue(run)) {
                return true;
            }
        }
        return false;
    }

    private record ImportRun(Server server, String jobId, ImportedAddressRetention retention, ProgressCounters counters) {}

    private static final class ProgressCounters {
        private int processed;
        private int skipped;
        private int handledWhenAnnounced;
        private Instant lastAnnouncedAt;
        private @Nullable Integer total;

        private ProgressCounters(Instant startedAt) {
            lastAnnouncedAt = startedAt;
        }

        private boolean dueForAnnounce(Instant now) {
            return handled() - handledWhenAnnounced >= PROGRESS_UPDATE_INTERVAL
                || !now.isBefore(lastAnnouncedAt.plus(PROGRESS_UPDATE_MAX_DELAY));
        }

        private void announced(Instant at) {
            handledWhenAnnounced = handled();
            lastAnnouncedAt = at;
        }

        private int handled() {
            return processed + skipped;
        }

        private void addProcessed(int value) {
            processed += value;
        }

        private void addSkipped(int value) {
            skipped += value;
        }

        private void skipOne() {
            skipped++;
        }

        private ImportedCounts snapshot() {
            return new ImportedCounts(processed, skipped, total);
        }

        private int processed() {
            return processed;
        }

        private @Nullable Integer total() {
            return total;
        }

        private void total(@Nullable Integer value) {
            total = value;
        }
    }

    private static final class MigrationDataException extends RuntimeException {
        private final String reason;

        private MigrationDataException(String reason) {
            super(reason);
            this.reason = reason;
        }

        private String reason() {
            return reason;
        }
    }
}
