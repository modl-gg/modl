package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.SessionPublicId;
import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;

class PanelSessionRepositoryContractTest {
    private static final String EMAIL = "mod@example.com";
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant LATER = NOW.plus(Duration.ofDays(14));

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aSessionRoundTripsByItsPublicId(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            PanelSession session = new PanelSession(SessionPublicId.of("raw-id"), EMAIL, "digest", "2001:db8::1",
                "Browser/1.0 (été 🚀)", NOW, LATER, AuthMethod.PASSWORD, true);

            sessions.insert(session);

            assertThat(sessions.findByPublicId(session.publicId())).contains(session);
            assertThat(sessions.findByPublicId("raw-id")).isEmpty();
            assertThat(sessions.findByPublicId(session.publicId().toUpperCase(Locale.ROOT))).isEmpty();
        }
    }

    @SqlContractTest
    void optionalColumnsMayBeAbsent(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            PanelSession session = new PanelSession("public", EMAIL, "digest", null, null, NOW, LATER, AuthMethod.EMAIL_CODE, false);

            sessions.insert(session);

            assertThat(sessions.findByPublicId("public")).contains(session);
        }
    }

    @SqlContractTest
    void aSessionStopsBeingReadableAtItsExpiry(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            sessions.insert(session("public", EMAIL, NOW, NOW.plusSeconds(1)));

            database.clock().advance(Duration.ofSeconds(1));

            assertThat(sessions.findByPublicId("public")).isEmpty();
            assertThat(sessions.findByEmail(EMAIL, database.clock().instant())).isEmpty();
            assertThat(sessions.findActivePublicIds(List.of("public"), database.clock().instant(), false)).isEmpty();
            assertThat(database.dsl().fetchCount(PANEL_SESSIONS)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void refreshMovesTheExpiryOfALiveSessionOnly(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            sessions.insert(session("live", EMAIL, NOW, NOW.plusSeconds(60)));
            sessions.insert(session("expired", EMAIL, NOW, NOW));

            assertThat(sessions.refresh("live", LATER)).isTrue();
            assertThat(sessions.refresh("live", LATER)).isTrue();
            assertThat(sessions.refresh("expired", LATER)).isFalse();
            assertThat(sessions.refresh("unknown", LATER)).isFalse();

            assertThat(expiryOf(database, "live")).isEqualTo(LATER);
            assertThat(expiryOf(database, "expired")).isEqualTo(NOW);
        }
    }

    @SqlContractTest
    void aRevokedSessionCannotBeRefreshedBackToLife(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            sessions.insert(session("public", EMAIL, NOW, LATER));

            assertThat(sessions.deleteByPublicId("public")).isTrue();

            assertThat(sessions.refresh("public", LATER)).isFalse();
            assertThat(sessions.deleteByPublicId("public")).isFalse();
            assertThat(database.dsl().fetchCount(PANEL_SESSIONS)).isZero();
        }
    }

    @SqlContractTest
    void listingHoldsOnlyTheLiveSessionsOfThatEmailInAStableOrder(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            sessions.insert(session("c", EMAIL, NOW, LATER));
            sessions.insert(session("a", EMAIL, NOW, LATER));
            sessions.insert(session("b", EMAIL, NOW.minusSeconds(1), LATER));
            sessions.insert(session("expired", EMAIL, NOW, NOW));
            sessions.insert(session("theirs", "other@example.com", NOW, LATER));

            assertThat(sessions.findByEmail(" Mod@Example.com", NOW)).extracting(PanelSession::publicId)
                .containsExactly("b", "a", "c");
        }
    }

    @SqlContractTest
    void activeIdsAndDeletionWorkAcrossChunks(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            List<String> ids = IntStream.range(0, InClauseChunks.CHUNK_SIZE + 2).mapToObj(index -> "session-" + index).toList();
            database.unitOfWork().write(() -> ids.forEach(id -> sessions.insert(session(id, EMAIL, NOW, LATER))));
            sessions.insert(session("expired", EMAIL, NOW, NOW));
            sessions.insert(session("kept", EMAIL, NOW, LATER));
            List<String> asked = Stream.concat(ids.stream(), Stream.of("expired", "unknown")).toList();

            assertThat(sessions.findActivePublicIds(asked, NOW, false)).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(sessions.findActivePublicIds(List.of(), NOW, false)).isEmpty();

            assertThat(sessions.deleteByPublicIds(asked)).isEqualTo(ids.size() + 1);
            assertThat(database.dsl().fetch(PANEL_SESSIONS).getValues(PANEL_SESSIONS.ID_HASH)).containsExactly("kept");
        }
    }

    @SqlContractTest
    void deletingByEmailTakesEverySessionOfThatEmailOnly(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            sessions.insert(session("live", "Mod@Example.com", NOW, LATER));
            sessions.insert(session("expired", EMAIL, NOW, NOW));
            sessions.insert(session("theirs", "other@example.com", NOW, LATER));

            assertThat(sessions.deleteByEmail("MOD@example.com")).isEqualTo(2);
            assertThat(sessions.deleteByEmail(" ")).isZero();
            assertThat(database.dsl().fetch(PANEL_SESSIONS).getValues(PANEL_SESSIONS.ID_HASH)).containsExactly("theirs");
        }
    }

    @SqlContractTest
    void aDuplicateIdOrAnOverlongColumnIsRefused(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            sessions.insert(session("public", EMAIL, NOW, LATER));

            assertThatThrownBy(() -> sessions.insert(session("public", "other@example.com", NOW, LATER)))
                .isInstanceOf(DuplicateRowException.class);
            assertThatThrownBy(() -> sessions.insert(session("p".repeat(65), EMAIL, NOW, LATER)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> sessions.insert(session("email", "e".repeat(255), NOW, LATER)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> sessions.insert(new PanelSession("key", EMAIL, "k".repeat(256), null, null, NOW, LATER, AuthMethod.EMAIL_CODE, false)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> sessions.insert(new PanelSession("ip", EMAIL, "digest", "1".repeat(46), null, NOW, LATER, AuthMethod.EMAIL_CODE, false)))
                .isInstanceOf(ValueTooLongException.class);
            assertThat(sessions.findByPublicId("public")).get().extracting(PanelSession::email).isEqualTo(EMAIL);
            assertThat(sessions.findByPublicId("p".repeat(65))).isEmpty();
        }
    }

    @SqlContractTest
    void theSecondFactorFlagIsSetClearedAndFilteredPerAccount(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            sessions.insert(session("mine", EMAIL, NOW, LATER));
            sessions.insert(session("second", EMAIL, NOW, LATER));
            sessions.insert(session("theirs", "other@example.com", NOW, LATER));

            assertThat(sessions.markSecondFactorVerified("mine")).isTrue();
            assertThat(sessions.markSecondFactorVerified("unknown")).isFalse();
            assertThat(sessions.findActivePublicIds(List.of("mine", "second", "theirs"), NOW, true)).containsExactly("mine");
            assertThat(sessions.findActivePublicIds(List.of("mine", "second", "theirs"), NOW, false)).hasSize(3);

            assertThat(sessions.clearSecondFactorVerified("Mod@Example.com")).isEqualTo(1);
            assertThat(sessions.findByPublicId("mine")).get().extracting(PanelSession::secondFactorVerified).isEqualTo(false);
        }
    }

    @SqlContractTest
    void deletingEveryOtherSessionKeepsTheCurrentOneAndOtherAccounts(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelSessionRepository sessions = AuthRepositories.on(database).sessions();
            sessions.insert(session("current", EMAIL, NOW, LATER));
            sessions.insert(session("old", EMAIL, NOW, LATER));
            sessions.insert(session("theirs", "other@example.com", NOW, LATER));

            assertThat(sessions.deleteByEmailExcept(EMAIL, "current")).isEqualTo(1);

            assertThat(database.dsl().fetch(PANEL_SESSIONS).getValues(PANEL_SESSIONS.ID_HASH)).containsExactlyInAnyOrder("current", "theirs");
        }
    }

    private static PanelSession session(String publicId, String email, Instant createdAt, Instant expiresAt) {
        return new PanelSession(publicId, email, "digest", "127.0.0.1", "JUnit", createdAt, expiresAt, AuthMethod.EMAIL_CODE, false);
    }

    private static Instant expiryOf(SqlTestDatabase database, String publicId) {
        return database.dsl().fetchSingle(PANEL_SESSIONS, PANEL_SESSIONS.ID_HASH.eq(publicId)).getExpiresAt();
    }
}
