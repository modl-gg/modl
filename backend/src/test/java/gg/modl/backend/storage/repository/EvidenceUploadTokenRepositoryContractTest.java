package gg.modl.backend.storage.repository;

import static gg.modl.backend.database.jooq.Tables.EVIDENCE_UPLOAD_TOKENS;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.ExpiredRowSweeper;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.storage.service.EvidenceUploadTokenService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.io.TempDir;

class EvidenceUploadTokenRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final String HASH = "ab".repeat(32);
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path directory;

    @SqlContractTest
    void aTokenRoundTripsAndStaysUntilItIsDeleted(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            EvidenceUploadTokenRepository tokens = new JooqEvidenceUploadTokenRepository(database.dsl(), database.unitOfWork());
            EvidenceUploadToken named = new EvidenceUploadToken(HASH, "PUN12345", PLAYER, "Mød", NOW, NOW.plusSeconds(1800));
            EvidenceUploadToken unnamed = new EvidenceUploadToken("cd".repeat(32), "PUN12345", PLAYER, null, NOW, NOW);
            tokens.insert(named);
            tokens.insert(unnamed);

            assertThat(tokens.findByTokenHash(HASH)).contains(named);
            assertThat(tokens.findByTokenHash(HASH)).contains(named);
            assertThat(tokens.findByTokenHash(unnamed.tokenHash())).contains(unnamed);
            assertThat(tokens.findByTokenHash(HASH.toUpperCase(Locale.ROOT))).isEmpty();

            assertThat(tokens.deleteByTokenHash(HASH)).isTrue();

            assertThat(tokens.deleteByTokenHash(HASH)).isFalse();
            assertThat(tokens.findByTokenHash(HASH)).isEmpty();
            assertThat(database.dsl().fetchCount(EVIDENCE_UPLOAD_TOKENS)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aHashTooLongToStoreFindsAndDeletesNothingInsteadOfFailing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            EvidenceUploadTokenRepository tokens = new JooqEvidenceUploadTokenRepository(database.dsl(), database.unitOfWork());
            String widest = "h".repeat(TextLimits.limit(EVIDENCE_UPLOAD_TOKENS.TOKEN_HASH));
            EvidenceUploadToken token = new EvidenceUploadToken(widest, "PUN12345", PLAYER, null, NOW, NOW);
            tokens.insert(token);

            assertThat(tokens.findByTokenHash(widest + "x")).isEmpty();
            assertThat(tokens.deleteByTokenHash(widest + "x")).isFalse();

            assertThat(tokens.findByTokenHash(widest)).contains(token);
        }
    }

    @SqlContractTest
    void theServiceStoresOnlyTheHashAndAnExpiredTokenIsInvalidAndSwept(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            EvidenceUploadTokenService service = new EvidenceUploadTokenService(
                new JooqEvidenceUploadTokenRepository(database.dsl(), database.unitOfWork()), database.clock());

            String token = service.createToken(
                TestServers.server("server", "admin@example.com"), "PUN12345", PLAYER, "Moderator");

            assertThat(database.dsl().select(EVIDENCE_UPLOAD_TOKENS.TOKEN_HASH).from(EVIDENCE_UPLOAD_TOKENS)
                .fetchSingle(EVIDENCE_UPLOAD_TOKENS.TOKEN_HASH)).isNotEqualTo(token).hasSize(64);
            database.clock().advance(Duration.ofMinutes(30));
            assertThat(service.validateToken(token)).isNotNull();

            database.clock().advance(Duration.ofMillis(1));

            assertThat(service.validateToken(token)).isNull();
            database.bean(ExpiredRowSweeper.class).sweep();
            assertThat(database.dsl().fetchCount(EVIDENCE_UPLOAD_TOKENS)).isZero();
        }
    }
}
