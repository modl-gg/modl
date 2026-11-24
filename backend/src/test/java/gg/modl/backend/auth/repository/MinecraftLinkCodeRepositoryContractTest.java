package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.MINECRAFT_LINK_CODES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.auth.link.MinecraftLinkCode;
import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class MinecraftLinkCodeRepositoryContractTest {
    private static final String EMAIL = "mod@example.com";
    private static final String OTHER = "other@example.com";
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant EXPIRES = NOW.plusSeconds(600);
    private static final int SIMULTANEOUS_ISSUES = 2;
    private static final int ROUNDS = 10;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aCodeRoundTrips(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MinecraftLinkCodeRepository codes = AuthRepositories.on(database).linkCodes();
            codes.replaceForIdentity(code("id-1", "Hash", "Mod@Example.com"));

            MinecraftLinkCode found = codes.findActive("Hash", NOW).orElseThrow();

            assertThat(found.getId()).isEqualTo("id-1");
            assertThat(found.getCodeHash()).isEqualTo("Hash");
            assertThat(found.getIdentityEmail()).isEqualTo(EMAIL);
            assertThat(found.getExpiresAt()).isEqualTo(EXPIRES);
            assertThat(found.getCreatedAt()).isEqualTo(NOW);
        }
    }

    @SqlContractTest
    void replacingLeavesOneCodePerIdentity(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            MinecraftLinkCodeRepository codes = AuthRepositories.on(database).linkCodes();
            codes.replaceForIdentity(code("id-1", "first", EMAIL));
            codes.replaceForIdentity(code("id-2", "theirs", OTHER));

            codes.replaceForIdentity(code("id-3", "second", EMAIL));

            assertThat(codes.findActive("first", NOW)).isEmpty();
            assertThat(codes.findActive("second", NOW)).get().extracting(MinecraftLinkCode::getIdentityEmail).isEqualTo(EMAIL);
            assertThat(codes.findActive("theirs", NOW)).get().extracting(MinecraftLinkCode::getIdentityEmail).isEqualTo(OTHER);
            assertThat(database.dsl().fetchCount(MINECRAFT_LINK_CODES)).isEqualTo(2);
        }
    }

    @SqlContractTest
    void aHashHeldByAnotherIdentityIsNeverRebound(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MinecraftLinkCodeRepository codes = AuthRepositories.on(database).linkCodes();
            codes.replaceForIdentity(code("id-1", "shared", EMAIL));
            codes.replaceForIdentity(code("id-2", "previous", OTHER));

            assertThatThrownBy(() -> codes.replaceForIdentity(code("id-3", "shared", OTHER)))
                .isInstanceOf(DuplicateRowException.class);

            assertThat(codes.findActive("shared", NOW)).get().extracting(MinecraftLinkCode::getIdentityEmail).isEqualTo(EMAIL);
            assertThat(codes.findActive("previous", NOW)).get().extracting(MinecraftLinkCode::getIdentityEmail).isEqualTo(OTHER);
        }
    }

    @SqlContractTest
    void anIdHeldByAnotherIdentityIsNeverRebound(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MinecraftLinkCodeRepository codes = AuthRepositories.on(database).linkCodes();
            codes.replaceForIdentity(code("id-1", "mine", EMAIL));

            assertThatThrownBy(() -> codes.replaceForIdentity(code("id-1", "theirs", OTHER)))
                .isInstanceOf(DuplicateRowException.class);

            assertThat(codes.findActive("mine", NOW)).get().extracting(MinecraftLinkCode::getIdentityEmail).isEqualTo(EMAIL);
            assertThat(codes.findActive("theirs", NOW)).isEmpty();
        }
    }

    @SqlContractTest
    void simultaneousIssuesForOneIdentityBothSucceedAndLeaveOneRow(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MinecraftLinkCodeRepository codes = AuthRepositories.on(database).linkCodes();
            for (int round = 0; round < ROUNDS; round++) {
                String prefix = "round-" + round + "-";
                SimultaneousCalls.run(SIMULTANEOUS_ISSUES, caller -> {
                    codes.replaceForIdentity(code(prefix + caller, prefix + caller, EMAIL));
                    return caller;
                });

                assertThat(database.dsl().fetch(MINECRAFT_LINK_CODES).getValues(MINECRAFT_LINK_CODES.ID))
                    .singleElement().asString().startsWith(prefix);
            }
        }
    }

    @SqlContractTest
    void aCodeIsConsumedOnceUnderConcurrency(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MinecraftLinkCodeRepository codes = AuthRepositories.on(database).linkCodes();
            codes.replaceForIdentity(code("id-1", "once", EMAIL));

            List<Boolean> consumed = SimultaneousCalls.run(SimultaneousCalls.CALLERS,
                caller -> codes.consumeActive("once", NOW).isPresent());

            assertThat(consumed).containsOnlyOnce(true);
            assertThat(database.dsl().fetchCount(MINECRAFT_LINK_CODES)).isZero();
        }
    }

    @SqlContractTest
    void anExpiredOrDifferentHashIsNeitherFoundNorConsumed(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MinecraftLinkCodeRepository codes = AuthRepositories.on(database).linkCodes();
            codes.replaceForIdentity(code("id-1", "Hash", EMAIL));

            assertThat(codes.findActive("Hash", EXPIRES)).isEmpty();
            assertThat(codes.consumeActive("Hash", EXPIRES)).isEmpty();
            assertThat(codes.consumeActive("hash", NOW)).isEmpty();
            assertThat(codes.findActive("h".repeat(256), NOW)).isEmpty();
            assertThat(database.dsl().fetchCount(MINECRAFT_LINK_CODES)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void deletingForAnIdentityLeavesTheOthers(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MinecraftLinkCodeRepository codes = AuthRepositories.on(database).linkCodes();
            codes.replaceForIdentity(code("id-1", "mine", EMAIL));
            codes.replaceForIdentity(code("id-2", "theirs", OTHER));

            codes.deleteForIdentity("MOD@example.com");

            assertThat(database.dsl().fetch(MINECRAFT_LINK_CODES).getValues(MINECRAFT_LINK_CODES.ID)).containsExactly("id-2");
        }
    }

    @SqlContractTest
    void overlongColumnsAreRefused(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MinecraftLinkCodeRepository codes = AuthRepositories.on(database).linkCodes();

            assertThatThrownBy(() -> codes.replaceForIdentity(code("i".repeat(37), "a", EMAIL))).isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> codes.replaceForIdentity(code("id-1", "h".repeat(256), EMAIL)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> codes.replaceForIdentity(code("id-2", "b", "e".repeat(255))))
                .isInstanceOf(ValueTooLongException.class);
        }
    }

    private static MinecraftLinkCode code(String id, String hash, String email) {
        return MinecraftLinkCode.builder()
            .id(id)
            .codeHash(hash)
            .identityEmail(email)
            .expiresAt(EXPIRES)
            .createdAt(NOW)
            .build();
    }
}
