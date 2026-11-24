package gg.modl.backend.staff.repository;

import static gg.modl.backend.database.jooq.Tables.INVITATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.staff.data.Invitation;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;

class InvitationRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant EXPIRES = NOW.plusSeconds(3600);
    private static final int CALLERS = 8;

    @TempDir
    Path dataDirectory;

    private SqlTestDatabase start(DialectTarget target) {
        return SqlTestDatabase.start(target, dataDirectory, new RetryInjection());
    }

    @SqlContractTest
    void anInvitationRoundTripsWithAGeneratedId(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            InvitationRepository invitations = StaffPorts.on(database).invitations();
            Invitation stored = invitation(null, "helper@example.com", "hash", EXPIRES);

            invitations.insert(stored);

            assertThat(stored.getId()).hasSize(16);
            assertThat(invitations.findById(stored.getId())).contains(stored);
            assertThat(invitations.findById("missing")).isEmpty();
        }
    }

    @SqlContractTest
    void aTokenHashBelongsToOneInvitation(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            InvitationRepository invitations = StaffPorts.on(database).invitations();
            invitations.insert(invitation("a", "a@example.com", "hash", EXPIRES));

            assertThatThrownBy(() -> invitations.insert(invitation("b", "b@example.com", "hash", EXPIRES)))
                .isInstanceOf(DuplicateRowException.class);
            assertThat(database.dsl().fetchCount(INVITATIONS)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void onlyUnexpiredInvitationsAreActive(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            InvitationRepository invitations = StaffPorts.on(database).invitations();
            invitations.insert(invitation("b", "B@Example.com", "hash-b", EXPIRES));
            invitations.insert(invitation("a", "a@example.com", "hash-a", EXPIRES));
            invitations.insert(invitation("ends-now", "now@example.com", "hash-now", NOW));

            assertThat(invitations.findActive(NOW)).extracting(Invitation::getId).containsExactly("a", "b");
            assertThat(invitations.countActive(NOW)).isEqualTo(2);
            assertThat(invitations.existsActiveByEmail("b@example.com", NOW)).isTrue();
            assertThat(invitations.existsActiveByEmail("B@EXAMPLE.COM", NOW)).isTrue();
            assertThat(invitations.existsActiveByEmail("now@example.com", NOW)).isFalse();
            assertThat(invitations.existsActiveByEmail("b@example.com", EXPIRES)).isFalse();
            assertThat(invitations.findById("ends-now")).isPresent();
        }
    }

    @SqlContractTest
    void consumingReturnsAndDeletesTheRow(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            InvitationRepository invitations = StaffPorts.on(database).invitations();
            Invitation stored = invitation("a", "a@example.com", "Hash", EXPIRES);
            invitations.insert(stored);

            assertThat(invitations.consumeActive("hash", NOW)).isEmpty();
            assertThat(invitations.consumeActive("Hash", EXPIRES)).isEmpty();
            assertThat(invitations.consumeActive("Hash", NOW)).contains(stored);
            assertThat(invitations.consumeActive("Hash", NOW)).isEmpty();
            assertThat(database.dsl().fetchCount(INVITATIONS)).isZero();
        }
    }

    @SqlContractTest
    void aRolledBackUnitLeavesTheInvitationRedeemable(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            InvitationRepository invitations = StaffPorts.on(database).invitations();
            invitations.insert(invitation("a", "a@example.com", "hash", EXPIRES));

            assertThatThrownBy(() -> database.unitOfWork().write(() -> {
                assertThat(invitations.consumeActive("hash", NOW)).isPresent();
                throw new IllegalStateException("follow-up write failed");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(invitations.consumeActive("hash", NOW)).isPresent();
        }
    }

    @SqlContractTest
    void simultaneousConsumersGetTheInvitationOnce(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            InvitationRepository invitations = StaffPorts.on(database).invitations();
            invitations.insert(invitation("a", "a@example.com", "hash", EXPIRES));

            List<Optional<Invitation>> consumed = StaffPorts.simultaneously(CALLERS,
                caller -> invitations.consumeActive("hash", NOW));

            assertThat(consumed.stream().filter(Optional::isPresent)).hasSize(1);
        }
    }

    @SqlContractTest
    void rotationReplacesTheHashOnlyWhileItIsTheExpectedOne(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            InvitationRepository invitations = StaffPorts.on(database).invitations();
            invitations.insert(invitation("a", "a@example.com", "old", EXPIRES));
            Instant later = NOW.plusSeconds(10);

            assertThat(invitations.rotateToken("a", "stale", "new", EXPIRES, later)).isFalse();
            assertThat(invitations.rotateToken("a", "old", "new", EXPIRES.plusSeconds(60), later)).isTrue();

            assertThat(invitations.consumeActive("old", NOW)).isEmpty();
            assertThat(invitations.findById("a")).get().satisfies(found -> {
                assertThat(found.getTokenHash()).isEqualTo("new");
                assertThat(found.getExpiresAt()).isEqualTo(EXPIRES.plusSeconds(60));
                assertThat(found.getUpdatedAt()).isEqualTo(later);
                assertThat(found.getCreatedAt()).isEqualTo(NOW);
            });
        }
    }

    @SqlContractTest
    void deletingReportsWhetherARowWentAway(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            InvitationRepository invitations = StaffPorts.on(database).invitations();
            invitations.insert(invitation("a", "a@example.com", "hash", EXPIRES));

            assertThat(invitations.deleteById("a")).isTrue();
            assertThat(invitations.deleteById("a")).isFalse();
        }
    }

    @SqlContractTest
    void oversizedLookupValuesSimplyMiss(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            InvitationRepository invitations = StaffPorts.on(database).invitations();

            assertThat(invitations.findById("i".repeat(37))).isEmpty();
            assertThat(invitations.deleteById("i".repeat(37))).isFalse();
            assertThat(invitations.existsActiveByEmail("e".repeat(255), NOW)).isFalse();
            assertThat(invitations.consumeActive("h".repeat(256), NOW)).isEmpty();
            assertThat(invitations.rotateToken("i".repeat(37), "old", "new", EXPIRES, NOW)).isFalse();
        }
    }

    @SqlContractTest
    void anOversizedValueIsRejectedBeforeItReachesSql(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            InvitationRepository invitations = StaffPorts.on(database).invitations();

            assertThatThrownBy(() -> invitations.insert(invitation("i".repeat(37), "a@example.com", "hash", EXPIRES)))
                .isInstanceOf(ValueTooLongException.class);
            assertThat(database.dsl().fetchCount(INVITATIONS)).isZero();
        }
    }

    private static Invitation invitation(String id, String email, String tokenHash, Instant expiresAt) {
        return Invitation.builder()
            .id(id)
            .email(email)
            .roleId("helper")
            .tokenHash(tokenHash)
            .expiresAt(expiresAt)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();
    }
}
