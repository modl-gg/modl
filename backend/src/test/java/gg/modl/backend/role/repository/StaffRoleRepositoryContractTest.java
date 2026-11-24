package gg.modl.backend.role.repository;

import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLE_PERMISSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.staff.repository.StaffPorts;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class StaffRoleRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;

    @TempDir
    Path dataDirectory;

    private SqlTestDatabase start(DialectTarget target) {
        return SqlTestDatabase.start(target, dataDirectory, new RetryInjection());
    }

    @SqlContractTest
    void aRoleRoundTripsWithItsPermissions(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            StaffRole stored = role("custom-1", "Équipe Support", 5, "punishment.modify", "ticket.view.all");

            roles.save(stored);

            assertThat(roles.findById("custom-1")).contains(stored);
            assertThat(roles.findById("missing")).isEmpty();
        }
    }

    @SqlContractTest
    void aRoleWithoutPermissionsIsStillFound(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();

            roles.save(role("empty", "Empty", 4));

            assertThat(roles.findById("empty")).get().satisfies(found -> assertThat(found.getPermissions()).isEmpty());
        }
    }

    @SqlContractTest
    void savingAgainReplacesThePermissionsAndKeepsRankAndCreationTime(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            roles.save(role("custom-1", "Before", 5, "a", "b"));
            StaffRole staleRead = roles.findById("custom-1").orElseThrow();
            roles.updateOrder(Map.of("custom-1", 9));

            staleRead.setName("After");
            staleRead.setPermissions(List.of("c", "b", "c"));
            staleRead.setCreatedAt(NOW.plusSeconds(99));
            staleRead.setUpdatedAt(NOW.plusSeconds(1));
            roles.save(staleRead);

            assertThat(roles.findById("custom-1")).get().satisfies(found -> {
                assertThat(found.getName()).isEqualTo("After");
                assertThat(found.getPermissions()).containsExactly("b", "c");
                assertThat(found.getOrder()).isEqualTo(9);
                assertThat(found.getCreatedAt()).isEqualTo(NOW);
                assertThat(found.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
            });
            assertThat(roles.findByNameIgnoreCase("before")).isEmpty();
        }
    }

    @SqlContractTest
    void savingCannotTakeAnotherRolesNameAndLeavesBothUntouched(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            roles.save(role("one", "Support", 4, "a"));
            roles.save(role("two", "Builder", 5, "b"));

            assertThatThrownBy(() -> roles.save(role("two", "SUPPORT", 5, "z"))).isInstanceOf(DuplicateRowException.class);
            assertThatThrownBy(() -> roles.save(role("three", "support", 6))).isInstanceOf(DuplicateRowException.class);

            assertThat(roles.findById("one")).contains(role("one", "Support", 4, "a"));
            assertThat(roles.findById("two")).contains(role("two", "Builder", 5, "b"));
        }
    }

    @SqlContractTest
    void namesMatchWithoutCase(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            roles.save(role("custom-1", "Équipe", 4));

            assertThat(roles.findByNameIgnoreCase("éQUIPE")).get().extracting(StaffRole::getId).isEqualTo("custom-1");
            assertThat(roles.existsByNameIgnoreCase("équipe")).isTrue();
            assertThat(roles.existsByNameIgnoreCaseExcludingId("équipe", "custom-1")).isFalse();
            assertThat(roles.existsByNameIgnoreCaseExcludingId("équipe", "custom-2")).isTrue();
            assertThat(roles.existsByNameIgnoreCase("equipe")).isFalse();
        }
    }

    @SqlContractTest
    void aNameOfSixtyFourCodePointsFitsWhateverItsLowerCaseForm(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            String dottedCapitals = "İ".repeat(64);
            String supplementary = "😀".repeat(64);

            roles.save(role("dotted", dottedCapitals, 4));
            roles.save(role("emoji", supplementary, 5));

            assertThat(roles.findById("dotted")).get().extracting(StaffRole::getName).isEqualTo(dottedCapitals);
            assertThat(roles.findByNameIgnoreCase(supplementary)).isPresent();
            assertThatThrownBy(() -> roles.save(role("long", "n".repeat(65), 6))).isInstanceOf(ValueTooLongException.class);
        }
    }

    @SqlContractTest
    void oversizedLookupValuesSimplyMiss(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            String tooLong = "r".repeat(65);
            roles.save(role("custom-1", "Support", 4));

            assertThat(roles.findById(tooLong)).isEmpty();
            assertThat(roles.findByNameIgnoreCase(tooLong)).isEmpty();
            assertThat(roles.findByIds(List.of(tooLong, "custom-1"))).extracting(StaffRole::getId).containsExactly("custom-1");
            assertThat(roles.existsByNameIgnoreCase(tooLong)).isFalse();
            assertThat(roles.existsByNameIgnoreCaseExcludingId("support", tooLong)).isTrue();
            assertThat(roles.deleteById(tooLong)).isFalse();
            roles.updateOrder(Map.of(tooLong, 1));
            assertThat(roles.findById("custom-1")).get().extracting(StaffRole::getOrder).isEqualTo(4);
        }
    }

    @SqlContractTest
    void seedingNeverOverwritesByIdOrByName(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            StaffRole edited = role("helper", "Trainee", 3, "edited");
            StaffRole sameName = role("custom-1", "Moderator", 4, "custom");
            roles.save(edited);
            roles.save(sameName);

            assertThat(roles.insertIfAbsent(role("helper", "Helper", 3, "default"))).isFalse();
            assertThat(roles.insertIfAbsent(role("moderator", "moderator", 2, "default"))).isFalse();
            assertThat(roles.insertIfAbsent(role("admin", "Admin", 1, "default"))).isTrue();

            assertThat(roles.findById("helper")).contains(edited);
            assertThat(roles.findById("custom-1")).contains(sameName);
            assertThat(roles.findById("moderator")).isEmpty();
            assertThat(roles.findById("admin")).contains(role("admin", "Admin", 1, "default"));
        }
    }

    @SqlContractTest
    void theListIsOrderedByRankThenCreationThenId(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            StaffRole later = role("a", "Later", 2);
            later.setCreatedAt(NOW.plusSeconds(1));
            roles.save(later);
            roles.save(role("c", "C", 2, "x", "y"));
            roles.save(role("b", "B", 2, "x"));
            roles.save(role("z", "Top", 1));

            assertThat(roles.findAllOrdered()).extracting(StaffRole::getId).containsExactly("z", "b", "c", "a");
            assertThat(roles.findHighestOrdered()).get().extracting(StaffRole::getId).isEqualTo("c");
        }
    }

    @SqlContractTest
    void theHighestOfAnEmptyTableIsNothing(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();

            assertThat(roles.findHighestOrdered()).isEmpty();
            assertThat(roles.findAllOrdered()).isEmpty();
        }
    }

    @SqlContractTest
    void rolesAreFetchedByManyIdsWithEveryPermission(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            List<String> ids = IntStream.range(0, 501).mapToObj(index -> "custom-" + index).toList();
            database.unitOfWork().write(() -> {
                ids.forEach(id -> roles.save(role(id, id, 4, "a", "b")));
            });

            assertThat(roles.findByIds(ids)).hasSize(501).allSatisfy(found -> assertThat(found.getPermissions()).containsExactly("a", "b"));
            assertThat(roles.findByIds(List.of())).isEmpty();
        }
    }

    @SqlContractTest
    void morePermissionsThanOneBatchAreStored(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            String[] permissions = IntStream.range(0, 501).mapToObj("punishment.apply.type-%03d"::formatted).toArray(String[]::new);

            roles.save(role("wide", "Wide", 4, permissions));

            assertThat(roles.findById("wide")).get().satisfies(found -> assertThat(found.getPermissions()).containsExactly(permissions));
        }
    }

    @SqlContractTest
    void reorderingChangesOnlyTheNamedRoles(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            roles.save(role("a", "A", 4));
            roles.save(role("b", "B", 5));
            roles.save(role("c", "C", 6));

            roles.updateOrder(Map.of("a", 5, "b", 4, "unknown", 1));

            assertThat(roles.findAllOrdered()).extracting(StaffRole::getId).containsExactly("b", "a", "c");
            assertThat(database.dsl().fetchCount(STAFF_ROLES)).isEqualTo(3);
        }
    }

    @SqlContractTest
    void repairTouchesOnlyCustomRolesStillAtZero(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            StaffRole builtIn = role("super-admin", "Super Admin", 0);
            builtIn.setDefault(true);
            roles.save(builtIn);
            roles.save(role("broken", "Broken", 0));
            roles.save(role("moved", "Moved", 7));

            assertThat(roles.findCustomRolesWithOrderZero()).extracting(StaffRole::getId).containsExactly("broken");

            roles.repairOrderFromZero(Map.of("super-admin", 8, "broken", 9, "moved", 10));

            assertThat(roles.findAllOrdered()).extracting(StaffRole::getOrder).containsExactly(0, 7, 9);
            assertThat(roles.findCustomRolesWithOrderZero()).isEmpty();
        }
    }

    @SqlContractTest
    void aLockedReadNeedsAWriteUnitAndReturnsTheWholeRole(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            StaffRole stored = role("custom-1", "Support", 4, "a", "b");
            roles.save(stored);

            assertThatThrownBy(() -> roles.findByIdForUpdate("custom-1")).isInstanceOf(IllegalStateException.class);
            assertThat(database.unitOfWork().write(() -> roles.findByIdForUpdate("custom-1"))).contains(stored);
            assertThat(database.unitOfWork().write(() -> roles.findByIdForUpdate("missing"))).isEmpty();
        }
    }

    @SqlContractTest
    void deletingARoleTakesItsPermissions(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRoleRepository roles = StaffPorts.on(database).roles();
            roles.save(role("doomed", "Doomed", 4, "a", "b"));
            roles.save(role("kept", "Kept", 5, "a"));

            assertThat(roles.deleteById("doomed")).isTrue();
            assertThat(roles.deleteById("doomed")).isFalse();

            assertThat(database.dsl().select(STAFF_ROLE_PERMISSIONS.ROLE_ID).from(STAFF_ROLE_PERMISSIONS)
                .fetch(STAFF_ROLE_PERMISSIONS.ROLE_ID)).containsExactly("kept");
        }
    }

    private static StaffRole role(String id, String name, int order, String... permissions) {
        return StaffRole.builder()
            .id(id)
            .name(name)
            .description("Description of " + id)
            .permissions(List.of(permissions))
            .isDefault(false)
            .order(order)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();
    }
}
