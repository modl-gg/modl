package gg.modl.backend.replay.repository;

import static gg.modl.backend.database.jooq.Tables.REPLAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayLabel;
import gg.modl.backend.replay.data.ReplayLabel.CheatDetail;
import gg.modl.backend.replay.data.ReplayLabel.TimeRange;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;

class JooqReplayRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final String TARGET = "3f8c9c5a-6b6e-4f2c-9b7f-1a2b3c4d5e6f";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aReplayRoundTripsWithItsNestedLabels(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayRepository repository = repository(database);
            ReplayDocument replay = replay("replay", ReplayDocument.STATUS_COMPLETE, NOW, "modl/replays/replay.modlreplay");
            replay.setMcVersion("1.21.11");
            replay.setTargetName("Target");
            replay.setFileSize(4096);
            replay.setLabels(List.of(label()));

            database.unitOfWork().write(() -> repository.insert(replay));

            assertThat(repository.findById("replay")).contains(replay);
            assertThat(repository.findById("missing")).isEmpty();
        }
    }

    @SqlContractTest
    void labelsAreReplacedAsAWhole(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayRepository repository = repository(database);
            database.unitOfWork().write(() -> repository.insert(replay("replay", ReplayDocument.STATUS_COMPLETE, NOW, "key")));
            assertThat(repository.findById("replay")).get().extracting(ReplayDocument::getLabels).isNull();

            Optional<ReplayDocument> labelled = database.unitOfWork().write(() -> repository.replaceLabels("replay", List.of(label())));
            assertThat(labelled).get().extracting(ReplayDocument::getLabels).isEqualTo(List.of(label()));

            database.unitOfWork().write(() -> repository.replaceLabels("replay", List.of()));
            assertThat(repository.findById("replay")).get().extracting(ReplayDocument::getLabels).isEqualTo(List.of());
            Optional<ReplayDocument> missing = database.unitOfWork().write(() -> repository.replaceLabels("missing", List.of()));
            assertThat(missing).isEmpty();
        }
    }

    @SqlContractTest
    void anUploadUpdateCanBeRepeated(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayRepository repository = repository(database);
            database.unitOfWork().write(() -> repository.insert(replay("replay", ReplayDocument.STATUS_PENDING, NOW, "key")));

            for (int attempt = 0; attempt < 2; attempt++) {
                assertThat(database.unitOfWork()
                    .write(() -> repository.updateUpload("replay", ReplayDocument.STATUS_COMPLETE, 99))
                    .booleanValue()).isTrue();
            }

            assertThat(repository.findById("replay")).get().satisfies(stored -> {
                assertThat(stored.getStatus()).isEqualTo(ReplayDocument.STATUS_COMPLETE);
                assertThat(stored.getFileSize()).isEqualTo(99);
            });
            assertThat(database.unitOfWork()
                .write(() -> repository.updateUpload("missing", ReplayDocument.STATUS_FAILED, 0))
                .booleanValue()).isFalse();
        }
    }

    @SqlContractTest
    void aPlayersReplaysAreNewestFirstWithAStableTieBreakAndAtMostOneHundred(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayRepository repository = repository(database);
            database.unitOfWork().write(() -> {
                IntStream.range(0, 105).forEach(index -> repository.insert(
                    replay("old-" + index, ReplayDocument.STATUS_COMPLETE, NOW.minusSeconds(index + 1), "key-" + index)));
                repository.insert(replay("a", ReplayDocument.STATUS_PENDING, NOW, "key-a"));
                repository.insert(replay("b", ReplayDocument.STATUS_FAILED, NOW, "key-b"));
                ReplayDocument other = replay("other", ReplayDocument.STATUS_COMPLETE, NOW.plusSeconds(1), "key-other");
                other.setTargetUuid("11111111-2222-3333-4444-555555555555");
                repository.insert(other);
            });

            List<ReplayDocument> listed = repository.findByTargetUuid(TARGET, 1000);

            assertThat(listed).hasSize(100);
            assertThat(listed.subList(0, 3)).extracting(ReplayDocument::getId).containsExactly("b", "a", "old-0");
            assertThat(repository.findByTargetUuid(TARGET, 2)).extracting(ReplayDocument::getId).containsExactly("b", "a");
        }
    }

    @SqlContractTest
    void onlyFinishedReplaysBeforeTheCutoffExpireSplitByStoredObject(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayRepository repository = repository(database);
            Instant expired = NOW.minusMillis(1);
            database.unitOfWork().write(() -> {
                repository.insert(replay("complete", ReplayDocument.STATUS_COMPLETE, expired, "key-complete"));
                repository.insert(replay("failed", ReplayDocument.STATUS_FAILED, expired, "key-failed"));
                repository.insert(replay("pending", ReplayDocument.STATUS_PENDING, expired, "key-pending"));
                repository.insert(replay("at-cutoff", ReplayDocument.STATUS_COMPLETE, NOW, "key-cutoff"));
                repository.insert(replay("no-key", ReplayDocument.STATUS_FAILED, expired, null));
                repository.insert(replay("empty-key", ReplayDocument.STATUS_COMPLETE, expired, ""));
            });

            assertThat(repository.findExpiredWithStorageKey(NOW, null, 10)).extracting(ReplayDocument::getId)
                .containsExactly("complete", "failed");
            assertThat(repository.findExpiredWithMissingStorageKey(NOW, null, 10)).extracting(ReplayDocument::getId)
                .containsExactly("empty-key", "no-key");
        }
    }

    @SqlContractTest
    void expiredPagesContinueAfterEqualTimestampsWithoutGapsOrRepeats(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayRepository repository = repository(database);
            Instant older = NOW.minusSeconds(20);
            Instant newer = NOW.minusSeconds(10);
            database.unitOfWork().write(() -> {
                for (String id : List.of("e", "c", "a", "d", "b")) {
                    repository.insert(replay(id, ReplayDocument.STATUS_COMPLETE, older, "key-" + id));
                }
                repository.insert(replay("0", ReplayDocument.STATUS_FAILED, newer, "key-0"));
            });

            List<String> seen = new ArrayList<>();
            ReplayCursor cursor = null;
            List<ReplayDocument> page;
            do {
                page = repository.findExpiredWithStorageKey(NOW, cursor, 2);
                page.forEach(replay -> seen.add(replay.getId()));
                if (!page.isEmpty()) {
                    cursor = new ReplayCursor(page.getLast().getCreatedAt(), page.getLast().getId());
                }
            } while (!page.isEmpty());

            assertThat(seen).containsExactly("a", "b", "c", "d", "e", "0");
        }
    }

    @SqlContractTest
    void lookupAndDeletionAcceptMoreThanOneChunk(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayRepository repository = repository(database);
            List<String> ids = IntStream.range(0, 501).mapToObj(index -> "replay-" + index).toList();
            database.unitOfWork().write(() -> {
                ids.forEach(id -> repository.insert(replay(id, ReplayDocument.STATUS_COMPLETE, NOW, "key-" + id)));
                repository.insert(replay("kept", ReplayDocument.STATUS_COMPLETE, NOW, "key-kept"));
            });

            assertThat(repository.findByStorageKeys(ids.stream().map(id -> "key-" + id).toList()))
                .extracting(ReplayDocument::getId)
                .containsExactlyInAnyOrderElementsOf(ids);
            assertThat(repository.findByStorageKeys(List.of())).isEmpty();

            assertThat(database.unitOfWork().write(() -> repository.deleteByIds(ids)).longValue()).isEqualTo(501);

            assertThat(database.dsl().select(REPLAYS.ID).from(REPLAYS).fetch(REPLAYS.ID)).containsExactly("kept");
            assertThat(database.unitOfWork().write(() -> repository.deleteByIds(List.of())).longValue()).isZero();
        }
    }

    @SqlContractTest
    void anOverlongTargetNameIsRejected(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayRepository repository = repository(database);
            ReplayDocument replay = replay("replay", ReplayDocument.STATUS_PENDING, NOW, "key");
            replay.setTargetName("x".repeat(TextLimits.limit(REPLAYS.TARGET_NAME) + 1));

            assertThatThrownBy(() -> database.unitOfWork().write(() -> repository.insert(replay)))
                .isInstanceOf(ValueTooLongException.class);
        }
    }

    private static JooqReplayRepository repository(SqlTestDatabase database) {
        return new JooqReplayRepository(database.dsl(), database.jsonColumns());
    }

    private static ReplayLabel label() {
        TimeRange range = new TimeRange();
        range.setStartMs(100);
        range.setEndMs(2500);
        CheatDetail cheat = new CheatDetail();
        cheat.setType("fly.$.vertical");
        cheat.setTimeRanges(List.of(range));
        ReplayLabel label = new ReplayLabel();
        label.setUuid(TARGET);
        label.setPlayerName("Target");
        label.setVerdict("cheating");
        label.setConfidence(87);
        label.setCheats(List.of(cheat));
        label.setNotes("clearly flying 🪶");
        return label;
    }

    private static ReplayDocument replay(String id, String status, Instant createdAt, @Nullable String storageKey) {
        ReplayDocument replay = new ReplayDocument();
        replay.setId(id);
        replay.setStatus(status);
        replay.setTargetUuid(TARGET);
        replay.setCreatedAt(createdAt);
        replay.setStorageKey(storageKey);
        return replay;
    }
}
