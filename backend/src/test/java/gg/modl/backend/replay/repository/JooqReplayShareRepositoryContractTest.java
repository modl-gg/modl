package gg.modl.backend.replay.repository;

import static gg.modl.backend.database.jooq.Tables.REPLAY_SHARES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayShare;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;

class JooqReplayShareRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aShareRoundTripsAndIsFoundByTokenHashAndReplay(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayShareRepository shares = shares(database);
            insertReplay(database, "replay");
            ReplayShare share = share("s1", "replay", "a".repeat(64), NOW, NOW.plusSeconds(86_400));

            assertThat(database.unitOfWork().write(() -> shares.insertWithinLimit(share, 25, 25, NOW))).isTrue();

            assertThat(shares.findByTokenHash("a".repeat(64))).contains(share);
            assertThat(shares.findForReplay("replay", "s1")).contains(share);
            assertThat(shares.findForReplay("other", "s1")).isEmpty();
            assertThat(shares.findByTokenHash("b".repeat(64))).isEmpty();
        }
    }

    @SqlContractTest
    void sharesAreListedNewestFirstWithAUniqueTieBreak(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayShareRepository shares = shares(database);
            insertReplay(database, "replay");
            insertReplay(database, "other");
            database.unitOfWork().write(() -> {
                insert(shares, share("s1", "replay", "a".repeat(64), NOW, null));
                insert(shares, share("s3", "replay", "b".repeat(64), NOW, null));
                insert(shares, share("s2", "replay", "c".repeat(64), NOW.plusSeconds(1), null));
                insert(shares, share("s4", "other", "d".repeat(64), NOW.plusSeconds(2), null));
            });

            assertThat(shares.listForReplay("replay")).extracting(ReplayShare::id).containsExactly("s2", "s3", "s1");
            assertThat(shares.listForReplay("other")).extracting(ReplayShare::id).containsExactly("s4");
        }
    }

    @SqlContractTest
    void revokingIsOnceAndViewsAreCounted(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayShareRepository shares = shares(database);
            insertReplay(database, "replay");
            database.unitOfWork().write(() -> insert(shares, share("s1", "replay", "a".repeat(64), NOW, null)));
            Instant viewed = NOW.plusSeconds(30);

            database.unitOfWork().write(() -> shares.addViews("s1", 1, NOW.plusSeconds(10)));
            database.unitOfWork().write(() -> shares.addViews("s1", 2, viewed));
            assertThat(database.unitOfWork().write(() -> shares.revoke("s1", NOW.plusSeconds(60)))).isTrue();
            assertThat(database.unitOfWork().write(() -> shares.revoke("s1", NOW.plusSeconds(90)))).isFalse();

            ReplayShare stored = shares.findForReplay("replay", "s1").orElseThrow();
            assertThat(stored.viewCount()).isEqualTo(3);
            assertThat(stored.lastViewedAt()).isEqualTo(viewed);
            assertThat(stored.revokedAt()).isEqualTo(NOW.plusSeconds(60));
        }
    }

    @SqlContractTest
    void deletingTheReplayDeletesItsShares(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayShareRepository shares = shares(database);
            JooqReplayRepository replays = new JooqReplayRepository(database.dsl(), database.jsonColumns());
            insertReplay(database, "replay");
            database.unitOfWork().write(() -> insert(shares, share("s1", "replay", "a".repeat(64), NOW, null)));

            database.unitOfWork().write(() -> replays.deleteByIds(List.of("replay")));

            assertThat(shares.findByTokenHash("a".repeat(64))).isEmpty();
            assertThat(shares.listForReplay("replay")).isEmpty();
        }
    }

    @SqlContractTest
    void lookupsOverTheColumnLimitMatchNothingAndWritesAreRefused(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayShareRepository shares = shares(database);
            insertReplay(database, "replay");
            String tooLong = "x".repeat(TextLimits.limit(REPLAY_SHARES.TOKEN_HASH) + 1);

            assertThat(shares.findByTokenHash(tooLong)).isEmpty();
            assertThat(shares.listForReplay("r".repeat(TextLimits.limit(REPLAY_SHARES.REPLAY_ID) + 1))).isEmpty();
            assertThatThrownBy(() -> database.unitOfWork().write(() -> insert(shares, share("s1", "replay", tooLong, NOW, null))))
                .isInstanceOf(ValueTooLongException.class);
        }
    }

    @SqlContractTest
    void onlyOpenLinksCountTowardTheLimitAndOldClosedLinksArePruned(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayShareRepository shares = shares(database);
            insertReplay(database, "replay");
            Instant later = NOW.plusSeconds(3600);
            database.unitOfWork().write(() -> {
                insert(shares, share("open", "replay", "a".repeat(64), NOW, null));
                insert(shares, share("expired", "replay", "b".repeat(64), NOW.plusSeconds(1), NOW.plusSeconds(60)));
                insert(shares, share("revoked", "replay", "c".repeat(64), NOW.plusSeconds(2), null));
                shares.revoke("revoked", NOW.plusSeconds(3));
            });

            assertThat(database.unitOfWork().write(() -> shares.insertWithinLimit(share("full", "replay", "d".repeat(64), later, null),
                1, 5, later))).isFalse();
            assertThat(database.unitOfWork().write(() -> shares.insertWithinLimit(share("next", "replay", "e".repeat(64), later, null),
                2, 1, later))).isTrue();

            assertThat(shares.listForReplay("replay")).extracting(ReplayShare::id).containsExactly("next", "revoked", "open");
        }
    }

    @SqlContractTest
    void onlyReplaysWithALinkThatStillOpensAreFoundAsShared(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqReplayShareRepository shares = shares(database);
            List.of("never", "later", "expired", "revoked", "unshared").forEach(id -> insertReplay(database, id));
            database.unitOfWork().write(() -> {
                insert(shares, share("s1", "never", "a".repeat(64), NOW, null));
                insert(shares, share("s2", "later", "b".repeat(64), NOW, NOW.plusSeconds(60)));
                insert(shares, share("s3", "expired", "c".repeat(64), NOW, NOW));
                insert(shares, share("s4", "revoked", "d".repeat(64), NOW, null));
                insert(shares, share("s5", "never", "e".repeat(64), NOW, NOW));
            });
            database.unitOfWork().write(() -> shares.revoke("s4", NOW));

            assertThat(shares.findReplayIdsWithOpenShare(List.of("never", "later", "expired", "revoked", "unshared", "x".repeat(200)), NOW))
                .containsExactlyInAnyOrder("never", "later");
            assertThat(shares.findReplayIdsWithOpenShare(List.of("later"), NOW.plusSeconds(60))).isEmpty();
            assertThat(shares.findReplayIdsWithOpenShare(List.of(), NOW)).isEmpty();
        }
    }

    private static void insert(JooqReplayShareRepository shares, ReplayShare share) {
        assertThat(shares.insertWithinLimit(share, Integer.MAX_VALUE, Integer.MAX_VALUE, share.createdAt())).isTrue();
    }

    private static JooqReplayShareRepository shares(SqlTestDatabase database) {
        return new JooqReplayShareRepository(database.dsl(), database.childRows());
    }

    private static void insertReplay(SqlTestDatabase database, String id) {
        ReplayDocument replay = new ReplayDocument();
        replay.setId(id);
        replay.setStatus(ReplayDocument.STATUS_COMPLETE);
        replay.setCreatedAt(NOW);
        replay.setStorageKey("modl_instance/replays/" + id + ".modlreplay");
        JooqReplayRepository replays = new JooqReplayRepository(database.dsl(), database.jsonColumns());
        database.unitOfWork().write(() -> replays.insert(replay));
    }

    private static ReplayShare share(String id, String replayId, String tokenHash, Instant createdAt, @Nullable Instant expiresAt) {
        return new ReplayShare(id, replayId, tokenHash, "Moderator", createdAt, expiresAt, null, 0L, null);
    }
}
