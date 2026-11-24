package gg.modl.backend.staff.repository;

import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.STAFF_TICKET_SUBSCRIPTIONS;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.staff.repository.StaffPorts.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.ReferenceViolationException;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class StaffRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final String UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OTHER_UUID = "11111111-2222-3333-4444-555555555555";
    private static final int CALLERS = 8;

    @TempDir
    Path dataDirectory;

    private SqlTestDatabase start(DialectTarget target) {
        return SqlTestDatabase.start(target, dataDirectory, new RetryInjection());
    }

    @SqlContractTest
    void aFullAggregateRoundTrips(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            Staff stored = Staff.builder()
                .email("mod@example.com")
                .username("Mod 😀")
                .roleId("moderator")
                .assignedMinecraftUuid(UUID)
                .assignedMinecraftUsername("ModMc")
                .language("de")
                .dateFormat("DD/MM/YYYY")
                .ticketSubscriptionSettings(Staff.TicketSubscriptionSettings.builder()
                    .enabled(false)
                    .frequency("daily")
                    .emailNotifications(Staff.NotificationSettings.builder()
                        .enabled(true)
                        .ticketReplies(true)
                        .subscribedTypes(List.of("a.b$c", "état"))
                        .build())
                    .build())
                .twoFactorTokenHash("token-hash")
                .twoFactorTokenIp("127.0.0.1")
                .twoFactorTokenCreatedAt(NOW.toEpochMilli())
                .twoFactorPendingDelivery(true)
                .twoFactorSessionIp("::1")
                .twoFactorSessionExpiresAt(NOW.plusSeconds(60).toEpochMilli())
                .lastSeen(NOW.minusSeconds(5))
                .createdAt(NOW)
                .updatedAt(NOW.plusMillis(7))
                .build();

            assertThat(staff.insert(stored)).isEqualTo(StaffInsertOutcome.INSERTED);

            assertThat(stored.getId()).hasSize(16);
            assertThat(staff.findById(stored.getId())).contains(stored);
        }
    }

    @SqlContractTest
    void aBareRowKeepsItsDefaults(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            Staff stored = member("mod@example.com", "mod");

            staff.insert(stored);

            assertThat(staff.findById(stored.getId())).get().satisfies(found -> {
                assertThat(found.getLanguage()).isEqualTo("en");
                assertThat(found.getDateFormat()).isEqualTo(Staff.DEFAULT_DATE_FORMAT);
                assertThat(found.getTicketSubscriptionSettings()).isNull();
                assertThat(found.getTwoFactorTokenHash()).isNull();
                assertThat(found.isTwoFactorPendingDelivery()).isFalse();
                assertThat(found.getLastSeen()).isNull();
            });
        }
    }

    @SqlContractTest
    void aTakenEmailOrUsernameIsReportedWithoutAViolation(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("a@example.com", "alice"));

            assertThat(staff.insert(member("a@example.com", "other"))).isEqualTo(StaffInsertOutcome.EMAIL_TAKEN);
            assertThat(staff.insert(member("b@example.com", "alice"))).isEqualTo(StaffInsertOutcome.USERNAME_TAKEN);
            assertThat(staff.countAll()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void emailTakenWinsWhenTwoRowsConflict(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("1", "bob@example.com", "bob"));
            staff.insert(member("2", "alice@example.com", "alice"));

            assertThat(staff.insert(member("alice@example.com", "bob"))).isEqualTo(StaffInsertOutcome.EMAIL_TAKEN);
            assertThat(staff.insert(member("bob@example.com", "alice"))).isEqualTo(StaffInsertOutcome.EMAIL_TAKEN);
        }
    }

    @SqlContractTest
    void anyOtherUniqueKeyIsAConflict(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            Staff holder = member("held", "a@example.com", "a");
            holder.setAssignedMinecraftUuid(UUID);
            holder.setTwoFactorTokenHash("hash");
            staff.insert(holder);

            Staff sameId = member("held", "b@example.com", "b");
            Staff sameUuid = member("c@example.com", "c");
            sameUuid.setAssignedMinecraftUuid(UUID);
            Staff sameToken = member("d@example.com", "d");
            sameToken.setTwoFactorTokenHash("hash");

            assertThat(staff.insert(sameId)).isEqualTo(StaffInsertOutcome.CONFLICT);
            assertThat(staff.insert(sameUuid)).isEqualTo(StaffInsertOutcome.CONFLICT);
            assertThat(staff.insert(sameToken)).isEqualTo(StaffInsertOutcome.CONFLICT);
            assertThat(staff.countAll()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aUsernameCollisionLeavesTheUnitUsableForASecondInsert(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("first@example.com", "helper"));

            database.unitOfWork().write(() -> {
                assertThat(staff.insert(member("second@example.com", "helper"))).isEqualTo(StaffInsertOutcome.USERNAME_TAKEN);
                assertThat(staff.insert(member("second@example.com", "helper1"))).isEqualTo(StaffInsertOutcome.INSERTED);
            });

            assertThat(staff.findByEmail("second@example.com")).get().extracting(Staff::getUsername).isEqualTo("helper1");
        }
    }

    @SqlContractTest
    void simultaneousInsertsOfOneEmailStoreOneRow(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            StaffRepository staff = StaffPorts.on(database).staff();

            List<StaffInsertOutcome> outcomes = StaffPorts.simultaneously(CALLERS,
                caller -> staff.insert(member("same@example.com", "user" + caller)));

            assertThat(outcomes).containsOnly(StaffInsertOutcome.INSERTED, StaffInsertOutcome.EMAIL_TAKEN)
                .containsOnlyOnce(StaffInsertOutcome.INSERTED);
            assertThat(staff.countAll()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void emailsAreStoredAndMatchedNormalised(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("  Mod@Example.COM ", "mod"));

            assertThat(staff.findByEmail("MOD@example.com")).get().extracting(Staff::getEmail).isEqualTo("mod@example.com");
            assertThat(staff.existsByEmail("mod@EXAMPLE.com")).isTrue();
            assertThat(staff.findUsernameByEmail("Mod@Example.com")).contains("mod");
            assertThat(staff.existsByEmail("other@example.com")).isFalse();
        }
    }

    @SqlContractTest
    void existenceChecksHonourTheirExclusions(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("one", "a@example.com", "alice"));

            assertThat(staff.existsByEmailExcluding("a@example.com", "A@Example.com")).isFalse();
            assertThat(staff.existsByEmailExcluding("a@example.com", "b@example.com")).isTrue();
            assertThat(staff.existsByUsername("alice")).isTrue();
            assertThat(staff.existsByUsername("Alice")).isFalse();
            assertThat(staff.existsByUsernameExcludingId("alice", "one")).isFalse();
            assertThat(staff.existsByUsernameExcludingId("alice", "two")).isTrue();
            assertThat(staff.existsByEmailOrUsername("a@example.com", "new")).isTrue();
            assertThat(staff.existsByEmailOrUsername("new@example.com", "alice")).isTrue();
            assertThat(staff.existsByEmailOrUsername("new@example.com", "new")).isFalse();
        }
    }

    @SqlContractTest
    void trailingSpacesMatchOnEveryDialect(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("a@example.com", "alice "));

            assertThat(staff.findByUsername("alice")).get().extracting(Staff::getUsername).isEqualTo("alice");
            assertThat(staff.existsByUsername("alice  ")).isTrue();
            assertThat(staff.insert(member("b@example.com", "alice"))).isEqualTo(StaffInsertOutcome.USERNAME_TAKEN);
        }
    }

    @SqlContractTest
    void writesEnforceCodePointLimitsWhileLookupsSimplyMiss(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            String widest = "😀".repeat(64);
            String tooLong = widest + "x";

            assertThat(staff.insert(member("wide@example.com", widest))).isEqualTo(StaffInsertOutcome.INSERTED);

            assertThat(staff.findByUsername(widest)).isPresent();
            assertThatThrownBy(() -> staff.insert(member("long@example.com", tooLong))).isInstanceOf(ValueTooLongException.class);
            assertThat(staff.findByUsername(tooLong)).isEmpty();
            assertThat(staff.findById("i".repeat(64))).isEmpty();
            assertThat(staff.findUsernamesByIds(List.of("i".repeat(64)))).isEmpty();
            staff.subscribe("wide@example.com", "t".repeat(65), NOW);
            assertThat(staff.unsubscribe("wide@example.com", "t".repeat(65))).isFalse();
            assertThat(staff.markSubscriptionRead("wide@example.com", "t".repeat(65), NOW)).isFalse();
            assertThat(staff.findSubscriptions("wide@example.com")).isEmpty();
        }
    }

    @SqlContractTest
    void listsAreOrderedByCreationThenId(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            Staff later = member("a", "later@example.com", "later");
            later.setCreatedAt(NOW.plusSeconds(1));
            staff.insert(later);
            staff.insert(member("c", "c@example.com", "c"));
            staff.insert(member("b", "b@example.com", "b"));

            assertThat(staff.findAll()).extracting(Staff::getId).containsExactly("b", "c", "a");
            assertThat(staff.findByUsernames(List.of("later", "c", "b", "nobody")))
                .extracting(Staff::getId).containsExactly("b", "c", "a");
        }
    }

    @SqlContractTest
    void lookupsByCollectionSpanMoreThanOneChunk(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            List<String> keys = IntStream.range(0, 501).mapToObj(index -> "member" + index).toList();
            database.unitOfWork().write(() -> {
                keys.forEach(key -> staff.insert(member(key, key + "@example.com", key)));
            });

            assertThat(staff.findByUsernames(keys)).hasSize(501);
            assertThat(staff.findUsernamesByIds(keys)).hasSize(501).containsEntry("member500", "member500");
            assertThat(staff.findByUsernames(List.of())).isEmpty();
        }
    }

    @SqlContractTest
    void eachIntentChangesOnlyItsOwnColumns(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            Staff original = member("id", "a@example.com", "alice");
            original.setAssignedMinecraftUuid(UUID);
            original.setAssignedMinecraftUsername("AliceMc");
            original.setTicketSubscriptionSettings(Staff.TicketSubscriptionSettings.builder().frequency("daily").build());
            original.setTwoFactorTokenHash("hash");
            original.setLastSeen(NOW.plusSeconds(3));
            staff.insert(original);
            Staff expected = staff.findById("id").orElseThrow();

            assertThat(staff.updateRole("id", "admin", NOW.plusSeconds(1))).isTrue();
            expected.setRoleId("admin");
            expected.setUpdatedAt(NOW.plusSeconds(1));
            assertThat(staff.findById("id")).contains(expected);

            assertThat(staff.updateProfile("id", "alicia", "fr", "YYYY-MM-DD", NOW.plusSeconds(2))).isTrue();
            expected.setUsername("alicia");
            expected.setLanguage("fr");
            expected.setDateFormat("YYYY-MM-DD");
            expected.setUpdatedAt(NOW.plusSeconds(2));
            assertThat(staff.findById("id")).contains(expected);

            assertThat(staff.updateEmail("id", " New@Example.com", NOW.plusSeconds(3))).isTrue();
            expected.setEmail("new@example.com");
            expected.setUpdatedAt(NOW.plusSeconds(3));
            assertThat(staff.findById("id")).contains(expected);

            assertThat(staff.updateMinecraftAssignment("id", OTHER_UUID, "AliciaMc", NOW.plusSeconds(4))).isTrue();
            expected.setAssignedMinecraftUuid(OTHER_UUID);
            expected.setAssignedMinecraftUsername("AliciaMc");
            expected.setUpdatedAt(NOW.plusSeconds(4));
            assertThat(staff.findById("id")).contains(expected);
        }
    }

    @SqlContractTest
    void aLockedReadNeedsAWriteUnitAndReturnsTheWholeRow(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            Staff stored = assigned("id", UUID);
            staff.insert(stored);

            assertThatThrownBy(() -> staff.findByIdForUpdate("id")).isInstanceOf(IllegalStateException.class);
            assertThat(database.unitOfWork().write(() -> staff.findByIdForUpdate("id"))).contains(stored);
            assertThat(database.unitOfWork().write(() -> staff.findByIdForUpdate("missing"))).isEmpty();
        }
    }

    @SqlContractTest
    void profileValuesThatAreNotGivenStayUnchanged(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("id", "a@example.com", "alice"));
            staff.updateProfile("id", "alicia", null, null, NOW);
            staff.updateProfile("id", null, "de", null, NOW);
            staff.updateProfile("id", null, null, "DD/MM/YYYY", NOW);

            assertThat(staff.findById("id")).get().satisfies(found -> {
                assertThat(found.getUsername()).isEqualTo("alicia");
                assertThat(found.getLanguage()).isEqualTo("de");
                assertThat(found.getDateFormat()).isEqualTo("DD/MM/YYYY");
            });
        }
    }

    @SqlContractTest
    void anIntentForARemovedRowChangesNothing(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();

            assertThat(staff.updateRole("missing", "admin", NOW)).isFalse();
            assertThat(staff.updateProfile("missing", "name", null, null, NOW)).isFalse();
            assertThat(staff.updateEmail("missing", "a@example.com", NOW)).isFalse();
            assertThat(staff.updateMinecraftAssignment("missing", UUID, "Mc", NOW)).isFalse();
            assertThat(staff.countAll()).isZero();
        }
    }

    @SqlContractTest
    void aMinecraftAccountBelongsToOneStaffMember(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            Staff holder = member("holder", "a@example.com", "a");
            holder.setAssignedMinecraftUuid(UUID.toUpperCase(Locale.ROOT));
            staff.insert(holder);
            Staff challenger = member("challenger", "b@example.com", "b");
            staff.insert(challenger);

            assertThatThrownBy(() -> staff.updateMinecraftAssignment("challenger", UUID, "Mc", NOW))
                .isInstanceOf(DuplicateRowException.class);
            assertThat(staff.findByAssignedMinecraftUuid(UUID.toUpperCase(Locale.ROOT))).get().extracting(Staff::getId).isEqualTo("holder");
            assertThat(staff.findByAssignedMinecraftUuidExcludingId(UUID, "holder")).isEmpty();
            assertThat(staff.findByAssignedMinecraftUuidExcludingId(UUID, "challenger")).isPresent();
            assertThat(staff.findByAssignedMinecraftUuidExcludingId(UUID, null)).isPresent();
        }
    }

    @SqlContractTest
    void anEmptyAssignmentIsStoredAsNullSoManyStaffCanHaveNone(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            Staff first = member("first", "a@example.com", "a");
            first.setAssignedMinecraftUuid("");
            first.setAssignedMinecraftUsername("");
            Staff second = member("second", "b@example.com", "b");
            second.setAssignedMinecraftUuid("");

            assertThat(staff.insert(first)).isEqualTo(StaffInsertOutcome.INSERTED);
            assertThat(staff.insert(second)).isEqualTo(StaffInsertOutcome.INSERTED);
            assertThat(staff.updateMinecraftAssignment("second", "", "", NOW)).isTrue();

            assertThat(database.dsl().select(STAFF.ASSIGNED_MINECRAFT_UUID).from(STAFF).fetch(STAFF.ASSIGNED_MINECRAFT_UUID))
                .containsExactly(null, null);
            assertThat(staff.findById("first")).get().extracting(Staff::getAssignedMinecraftUsername).isNull();
            assertThat(staff.findByAssignedMinecraftUuid("")).isEmpty();
            assertThat(staff.findAssignedMinecraftStaff()).isEmpty();
            assertThat(staff.findAssignedMinecraftUuids()).isEmpty();
        }
    }

    @SqlContractTest
    void assignedStaffAndTheirAccountsAreListed(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(assigned("b", OTHER_UUID));
            staff.insert(assigned("a", UUID));
            staff.insert(member("none", "none@example.com", "none"));

            assertThat(staff.findAssignedMinecraftStaff()).extracting(Staff::getId).containsExactly("a", "b");
            assertThat(staff.findAssignedMinecraftUuids()).containsExactly(OTHER_UUID, UUID);
        }
    }

    @SqlContractTest
    void lastSeenFollowsTheAssignedAccount(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(assigned("a", UUID));

            assertThat(staff.updateLastSeenByAssignedMinecraftUuid(UUID, NOW.plusSeconds(1))).isTrue();
            assertThat(staff.updateLastSeenByAssignedMinecraftUuid(UUID, NOW.plusSeconds(1))).isTrue();
            assertThat(staff.updateLastSeenByAssignedMinecraftUuid(OTHER_UUID, NOW)).isFalse();

            assertThat(staff.findById("a")).get().extracting(Staff::getLastSeen).isEqualTo(NOW.plusSeconds(1));
        }
    }

    @SqlContractTest
    void staffAreCountedPerRole(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("a@example.com", "a"));
            staff.insert(member("b@example.com", "b"));
            Staff admin = member("c@example.com", "c");
            admin.setRoleId("admin");
            staff.insert(admin);
            Staff roleless = member("d@example.com", "d");
            roleless.setRoleId(null);
            staff.insert(roleless);

            assertThat(staff.countByRoleId()).containsExactlyInAnyOrderEntriesOf(Map.of("helper", 2, "admin", 1));
            assertThat(staff.countByRoleId("helper")).isEqualTo(2);
            assertThat(staff.countByRoleId("ghost")).isZero();
            assertThat(staff.countAll()).isEqualTo(4);
        }
    }

    @SqlContractTest
    void aTwoFactorTokenActivatesOnce(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(assigned("a", UUID));

            assertThat(staff.createTwoFactorToken(UUID, "hash", "127.0.0.1", NOW)).isTrue();
            assertThat(staff.createTwoFactorToken(OTHER_UUID, "other", "127.0.0.1", NOW)).isFalse();
            assertThat(staff.findByTwoFactorTokenHash("HASH")).isEmpty();
            assertThat(staff.findByTwoFactorTokenHash("hash")).get().extracting(Staff::getId).isEqualTo("a");
            assertThat(staff.activateTwoFactorSession("a", "wrong", "127.0.0.1", NOW)).isFalse();
            assertThat(staff.activateTwoFactorSession("a", "hash", "127.0.0.1", NOW.plusSeconds(60))).isTrue();
            assertThat(staff.activateTwoFactorSession("a", "hash", "127.0.0.1", NOW.plusSeconds(99))).isFalse();

            assertThat(staff.findById("a")).get().satisfies(found -> {
                assertThat(found.getTwoFactorTokenHash()).isNull();
                assertThat(found.getTwoFactorTokenIp()).isNull();
                assertThat(found.getTwoFactorTokenCreatedAt()).isNull();
                assertThat(found.isTwoFactorPendingDelivery()).isTrue();
                assertThat(found.getTwoFactorSessionIp()).isEqualTo("127.0.0.1");
                assertThat(found.getTwoFactorSessionExpiresAt()).isEqualTo(NOW.plusSeconds(60).toEpochMilli());
            });
        }
    }

    @SqlContractTest
    void simultaneousActivationsOfOneTokenSucceedOnce(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(assigned("a", UUID));
            staff.createTwoFactorToken(UUID, "hash", "127.0.0.1", NOW);

            List<Boolean> activations = StaffPorts.simultaneously(CALLERS,
                caller -> staff.activateTwoFactorSession("a", "hash", "127.0.0.1", NOW.plusSeconds(60)));

            assertThat(activations).containsOnlyOnce(true);
        }
    }

    @SqlContractTest
    void clearingOnlyTouchesTheDeliveriesThatWereRead(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(assigned("read", UUID));
            staff.insert(assigned("later", OTHER_UUID));
            staff.createTwoFactorToken(UUID, "first", "127.0.0.1", NOW);
            staff.activateTwoFactorSession("read", "first", "127.0.0.1", NOW.plusSeconds(60));
            List<String> read = staff.findWithPendingTwoFactorDelivery().stream().map(Staff::getId).toList();

            staff.createTwoFactorToken(OTHER_UUID, "second", "127.0.0.1", NOW);
            staff.activateTwoFactorSession("later", "second", "127.0.0.1", NOW.plusSeconds(60));

            assertThat(read).containsExactly("read");
            assertThat(staff.clearPendingTwoFactorDelivery(read)).isEqualTo(1);
            assertThat(staff.clearPendingTwoFactorDelivery(read)).isZero();
            assertThat(staff.clearPendingTwoFactorDelivery(List.of())).isZero();
            assertThat(staff.findWithPendingTwoFactorDelivery()).extracting(Staff::getId).containsExactly("later");
        }
    }

    @SqlContractTest
    void aPendingDeliveryWithoutAnAccountIsNotDeliverable(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            Staff unlinked = member("a@example.com", "a");
            unlinked.setTwoFactorPendingDelivery(true);
            staff.insert(unlinked);

            assertThat(staff.findWithPendingTwoFactorDelivery()).isEmpty();
        }
    }

    @SqlContractTest
    void subscribingByEmailStoresOneRowAndKeepsTheFirstTime(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("a", "a@example.com", "a"));
            StaffPorts.insertTicket(database, "T-1");

            staff.subscribe("A@example.com", "T-1", NOW);
            staff.subscribe("a@example.com", "T-1", NOW.plusSeconds(30));

            assertThat(database.dsl().fetchCount(STAFF_TICKET_SUBSCRIPTIONS)).isEqualTo(1);
            assertThat(staff.findSubscriptions("a@example.com")).containsExactly(new TicketSubscription("T-1", NOW, null));
        }
    }

    @SqlContractTest
    void theSynthesizedSuperAdminHasNoSubscriptionsAndDoesNotFail(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            StaffPorts.insertTicket(database, "T-1");

            staff.subscribe("owner@example.com", "T-1", NOW);

            assertThat(database.dsl().fetchCount(STAFF_TICKET_SUBSCRIPTIONS)).isZero();
            assertThat(staff.markSubscriptionRead("owner@example.com", "T-1", NOW)).isFalse();
            assertThat(staff.unsubscribe("owner@example.com", "T-1")).isFalse();
            assertThat(staff.findSubscriptions("owner@example.com")).isEmpty();
        }
    }

    @SqlContractTest
    void readingMarksOnlyThatSubscriptionAndUnsubscribingDeletesIt(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("a", "a@example.com", "a"));
            staff.insert(member("b", "b@example.com", "b"));
            StaffPorts.insertTicket(database, "T-2");
            StaffPorts.insertTicket(database, "T-1");
            staff.subscribe("a@example.com", "T-2", NOW);
            staff.subscribe("a@example.com", "T-1", NOW);
            staff.subscribe("b@example.com", "T-1", NOW);

            assertThat(staff.markSubscriptionRead("a@example.com", "T-1", NOW.plusSeconds(5))).isTrue();

            assertThat(staff.findSubscriptions("a@example.com")).containsExactly(
                new TicketSubscription("T-1", NOW, NOW.plusSeconds(5)),
                new TicketSubscription("T-2", NOW, null));
            assertThat(staff.unsubscribe("a@example.com", "T-1")).isTrue();
            assertThat(staff.unsubscribe("a@example.com", "T-1")).isFalse();
            assertThat(staff.markSubscriptionRead("a@example.com", "T-1", NOW)).isFalse();
            assertThat(staff.findSubscriptions("a@example.com")).extracting(TicketSubscription::ticketId).containsExactly("T-2");
            assertThat(staff.findSubscriptions("b@example.com")).containsExactly(new TicketSubscription("T-1", NOW, null));
            assertThat(database.dsl().fetchCount(STAFF_TICKET_SUBSCRIPTIONS)).isEqualTo(2);
        }
    }

    @SqlContractTest
    void subscribingToATicketThatIsGoneIsAReferenceViolation(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("a@example.com", "a"));

            assertThatThrownBy(() -> staff.subscribe("a@example.com", "gone", NOW)).isInstanceOf(ReferenceViolationException.class);
        }
    }

    @SqlContractTest
    void subscriptionsGoWithTheStaffMemberAndWithTheTicket(DialectTarget target) {
        try (SqlTestDatabase database = start(target)) {
            StaffRepository staff = StaffPorts.on(database).staff();
            staff.insert(member("a", "a@example.com", "a"));
            staff.insert(member("b", "b@example.com", "b"));
            StaffPorts.insertTicket(database, "T-1");
            staff.subscribe("a@example.com", "T-1", NOW);
            staff.subscribe("b@example.com", "T-1", NOW);

            assertThat(staff.deleteById("a")).isTrue();
            assertThat(staff.deleteById("a")).isFalse();
            assertThat(database.dsl().fetchCount(STAFF_TICKET_SUBSCRIPTIONS)).isEqualTo(1);

            database.unitOfWork().write(() -> {
                database.dsl().deleteFrom(TICKETS).where(TICKETS.ID.eq("T-1")).execute();
            });
            assertThat(database.dsl().fetchCount(STAFF_TICKET_SUBSCRIPTIONS)).isZero();
        }
    }

    private static Staff assigned(String id, String uuid) {
        Staff staff = member(id, id + "@example.com", id);
        staff.setAssignedMinecraftUuid(uuid);
        return staff;
    }
}
