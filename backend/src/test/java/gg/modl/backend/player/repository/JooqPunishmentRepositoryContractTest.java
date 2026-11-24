package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;
import static gg.modl.backend.player.PunishmentStore.BAN;
import static gg.modl.backend.player.PunishmentStore.KICK;
import static gg.modl.backend.player.PunishmentStore.MUTE;
import static gg.modl.backend.player.PunishmentStore.NOW;
import static gg.modl.backend.player.PunishmentStore.change;
import static gg.modl.backend.player.PunishmentStore.draft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.GeneratedIdInsert;
import gg.modl.backend.database.ReferenceViolationException;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.infrastructure.proto.ProtoMapperSupport;
import gg.modl.backend.iplookup.settings.PlayerAddressSettings;
import gg.modl.backend.player.PunishmentStore;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.service.PlayerStatusCalculator;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;

class JooqPunishmentRepositoryContractTest {
    private static final int LINK_LIMIT = PlayerAddressSettings.DEFAULT_SHARED_ACCOUNT_LIMIT;
    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final String MANUAL_CHANGE = PunishmentModificationType.MANUAL_DURATION_CHANGE.name();
    private static final String MANUAL_PARDON = PunishmentModificationType.MANUAL_PARDON.name();
    private static final long RANDOM_SEED = 20_261_122L;
    private static final int GENERATED_PUNISHMENTS = 300;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void searchRowsCarryTheListedFieldsNewestFirst(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            String other = store.addPlayer("p2");
            Punishment older = draft("SEARCHA1", BAN, null, NOW, null);
            older.setIssuerName("Console");
            older.data().setReason("cheating");
            store.issue(player, older, ResolvedCategory.BAN);
            Punishment newest = draft("SEARCHA2", MUTE, HOUR, NOW, null);
            newest.setIssued(NOW.plusSeconds(60));
            store.issue(player, newest, ResolvedCategory.MUTE);
            Punishment otherPlayers = draft("SEARCHB1", KICK, null, NOW, null);
            otherPlayers.setIssued(NOW.plusSeconds(30));
            store.issue(other, otherPlayers, ResolvedCategory.NONE);

            PunishmentSearchRow found = store.punishments().findSearchRow("SEARCHA1").orElseThrow();

            assertThat(found.playerUsername()).isEqualTo("Player_p1");
            assertThat(found.typeOrdinal()).isEqualTo(BAN);
            assertThat(found.issuerName()).isEqualTo("Console");
            assertThat(found.reason()).isEqualTo("cheating");
            assertThat(found.issued()).isEqualTo(NOW);
            assertThat(found.isActiveAt(NOW.plusSeconds(Duration.ofDays(365).toSeconds()))).isTrue();
            assertThat(store.punishments().findSearchRow("MISSING1")).isEmpty();
            assertThat(store.punishments().findSearchRowsOfPlayer(player)).extracting(PunishmentSearchRow::id)
                .containsExactly("SEARCHA2", "SEARCHA1");
            assertThat(store.punishments().findRecentSearchRows(2)).extracting(PunishmentSearchRow::id)
                .containsExactly("SEARCHA2", "SEARCHB1");
        }
    }

    @SqlContractTest
    void lockingByIdReadsTheLockedRowAndMatchesNothingElse(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            store.issue(store.addPlayer("p1"), draft("LOCKED01", BAN, HOUR, NOW, null), ResolvedCategory.BAN);

            assertThat(database.unitOfWork().write(() -> store.punishments().lockById("LOCKED01")))
                .hasValueSatisfying(locked -> assertThat(locked.punishment().getId()).isEqualTo("LOCKED01"));
            assertThat(database.unitOfWork().write(() -> store.punishments().lockById("MISSING1"))).isEmpty();
            assertThat(database.unitOfWork().write(() -> store.punishments().lockById("x".repeat(600)))).isEmpty();
        }
    }

    @SqlContractTest
    void linkedBansOfAPlayerAreTheOnesOfThatPlayerForTheGivenParents(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String alt = store.addPlayer("alt");
            String other = store.addPlayer("other");
            store.issue(alt, linkedBan("LINKALT1", "PARENT01"), ResolvedCategory.BAN);
            store.issue(alt, linkedBan("LINKALT2", "PARENT02"), ResolvedCategory.BAN);
            store.issue(other, linkedBan("LINKOTH1", "PARENT01"), ResolvedCategory.BAN);

            assertThat(store.punishments().findLinkedBansOfPlayer(alt, List.of("PARENT01")))
                .extracting(Punishment::getId).containsExactly("LINKALT1");
            assertThat(store.punishments().findLinkedBansOfPlayer(alt, List.of("PARENT01", "PARENT02")))
                .extracting(Punishment::getId).containsExactly("LINKALT1", "LINKALT2");
            assertThat(store.punishments().findLinkedBansOfPlayer(alt, List.of())).isEmpty();
        }
    }

    @SqlContractTest
    void queuedPunishmentsCountOnlyForOnlinePlayers(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            store.addPlayers(List.of("online", "idle"), true);
            String offline = store.addPlayer("offline");
            store.issue("online", draft("ACTIVE01", BAN, HOUR, NOW, null), ResolvedCategory.BAN);
            store.issue("online", draft("QUEUED01", BAN, HOUR, null, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);
            store.issue(offline, draft("ACTIVE02", BAN, HOUR, NOW, null), ResolvedCategory.BAN);
            store.issue(offline, draft("QUEUED02", BAN, HOUR, null, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);
            store.issue("idle", draft("ACTIVE03", BAN, HOUR, NOW, null), ResolvedCategory.BAN);
            store.issue("idle", draft("QUEUED03", MUTE, HOUR, null, PunishmentStatus.UNSTARTED), ResolvedCategory.MUTE);

            assertThat(store.punishments().hasQueuedWhileOnline("online")).isTrue();
            assertThat(store.punishments().hasQueuedWhileOnline(offline)).isFalse();
            assertThat(store.punishments().findOnlinePlayersQueuedBehindExpired(NOW.plusSeconds(1800), NOW.plusSeconds(7200)))
                .containsExactly("online");
            assertThat(store.punishments().findOnlinePlayersQueuedBehindExpired(NOW.plusSeconds(7200), NOW.plusSeconds(9000)))
                .isEmpty();
        }
    }

    @SqlContractTest
    void aFullAggregateRoundTrips(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            Punishment issued = draft("AGGREGAT", BAN, DAY, NOW, null);
            issued.setIssuerId("staff-1");
            issued.data().setReason("griefing \u00c9 \ud83d\ude00");
            issued.data().setSeverity("severe");
            issued.data().setOffenseLevel("habitual");
            issued.data().setAltBlocking(true);
            issued.data().setWipeAfterExpiry(true);
            issued.data().setLinkedBanId("PARENTID");
            issued.data().setLinkedBanParentUuid(PunishmentStore.uuidOf("parent").toString());
            issued.data().setBlockedName("OldName");
            issued.data().setBlockedSkin("skin-hash");
            issued.data().setAppealOutcome("Rejected");
            issued.data().setAppealTicketId("APPEAL-1");
            issued.data().markStatWipeCompleted(NOW.plusSeconds(5));
            issued.data().asMap().put("silent", true);
            issued.data().asMap().put("a.b", Map.of("$key", List.of("x", "y")));
            PunishmentModification changed = new PunishmentModification("MOD00001", MANUAL_CHANGE, NOW.plusSeconds(1), null,
                "staff-1", "longer", 2 * DAY, "APPEAL-1", Map.of("nested.key", Map.of("$v", "1")));
            issued.getModifications().add(changed);
            issued.getNotes().add(new PunishmentNote("NOTE0001", "first", NOW, "Mod", null));
            issued.getNotes().add(new PunishmentNote("NOTE0002", "second", NOW, null, "staff-1"));
            issued.getEvidence().add(new PunishmentEvidence("screenshot", "https://example.com/e.png", "file", null, "staff-1",
                NOW, "e.png", "image/png", 2048L));
            issued.getAttachedTicketIds().addAll(List.of("T-2", "T-1", "T-2"));

            store.issue(player, issued, ResolvedCategory.BAN);

            PunishmentRecord found = store.punishments().findById("AGGREGAT").orElseThrow();
            Punishment read = found.punishment();
            assertThat(found.playerId()).isEqualTo(player);
            assertThat(found.playerMinecraftUuid()).isEqualTo(PunishmentStore.uuidOf(player).toString());
            assertThat(found.playerUsername()).isEqualTo("Player_p1");
            assertThat(found.version()).isZero();
            assertThat(read.getIssuerId()).isEqualTo("staff-1");
            assertThat(read.getIssued()).isEqualTo(NOW);
            assertThat(read.getStarted()).isEqualTo(NOW);
            assertThat(read.enforcementCategory()).isEqualTo(ResolvedCategory.BAN);
            assertThat(read.getModifications()).containsExactly(changed);
            assertThat(read.getNotes()).isEqualTo(issued.getNotes());
            assertThat(read.getEvidence()).isEqualTo(issued.getEvidence());
            assertThat(read.getAttachedTicketIds()).containsExactly("T-1", "T-2");
            assertThat(read.data().reason()).isEqualTo(issued.data().reason());
            assertThat(read.data().severity()).isEqualTo("severe");
            assertThat(read.data().offenseLevel()).isEqualTo("habitual");
            assertThat(read.data().duration()).isEqualTo(DAY);
            assertThat(read.data().altBlocking()).isTrue();
            assertThat(read.data().wipeAfterExpiry()).isTrue();
            assertThat(read.data().statWipeCompleted()).isTrue();
            assertThat(read.data().statWipeCompletedAt()).isEqualTo(NOW.plusSeconds(5));
            assertThat(read.data().linkedBanId()).isEqualTo("PARENTID");
            assertThat(read.data().linkedBanParentUuid()).isEqualTo(PunishmentStore.uuidOf("parent").toString());
            assertThat(read.data().blockedName()).isEqualTo("OldName");
            assertThat(read.data().blockedSkin()).isEqualTo("skin-hash");
            assertThat(read.data().appealOutcome()).isEqualTo("Rejected");
            assertThat(read.data().appealTicketId()).isEqualTo("APPEAL-1");
            assertThat(read.data().asMap()).containsEntry("silent", true)
                .containsEntry("a.b", Map.of("$key", List.of("x", "y")));
            assertThat(store.activeUntil("AGGREGAT")).isEqualTo(changed.date().plusMillis(2 * DAY));
            assertThat(store.modifiedAt("AGGREGAT")).isEqualTo(changed.date());
            assertThat(store.punishments().findByPlayer(player)).containsExactly(read);
        }
    }

    @SqlContractTest
    void aTakenIdIsLeftAloneAndAFreshOneIsGenerated(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            Punishment original = draft("TAKENAAA", BAN, null, null, null);
            store.issue(player, original, ResolvedCategory.BAN);

            Punishment second = draft("", MUTE, HOUR, null, null);
            second.getNotes().add(new PunishmentNote("N2", "second", NOW, "Mod", null));
            Iterator<String> candidates = List.of("TAKENAAA", "TAKENAAA", "FRESHBBB").iterator();
            String id = database.unitOfWork().write(() -> GeneratedIdInsert.insert(candidates::next, candidate -> {
                second.setId(candidate);
                return store.punishments().insertIfAbsent(player, second, ResolvedCategory.MUTE);
            }, GeneratedIdInsert.DEFAULT_ATTEMPTS));

            assertThat(id).isEqualTo("FRESHBBB");
            assertThat(store.punishment("TAKENAAA").getTypeOrdinal()).isEqualTo(BAN);
            assertThat(store.punishment("TAKENAAA").getNotes()).isEmpty();
            assertThat(store.punishment("FRESHBBB").getNotes()).hasSize(1);
        }
    }

    @SqlContractTest
    void theCategoryPassedToInsertIsTheStoredOne(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            for (ResolvedCategory category : ResolvedCategory.values()) {
                Punishment issued = draft("CAT-" + category, 99, null, null, null);
                issued.data().setEnforcementCategory(ResolvedCategory.BAN);
                store.issue(player, issued, category);

                assertThat(store.punishment(issued.getId()).enforcementCategory()).isEqualTo(category);
            }
        }
    }

    @SqlContractTest
    void anInsertWithoutCategoryOrDerivedExpiryFails(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            PunishmentsRecord withoutCategory = rawPunishment("RAW1", player);
            withoutCategory.setActiveUntilAt(NOW);
            PunishmentsRecord withoutExpiry = rawPunishment("RAW2", player);
            withoutExpiry.setEnforcementCategory(ResolvedCategory.BAN.name());

            for (PunishmentsRecord row : List.of(withoutCategory, withoutExpiry)) {
                assertThatThrownBy(() -> database.unitOfWork().write(
                    () -> database.dsl().insertInto(PUNISHMENTS).set(row).execute()))
                    .isInstanceOf(RuntimeException.class);
            }
            assertThat(database.dsl().fetchCount(PUNISHMENTS)).isZero();
        }
    }

    @SqlContractTest
    void zeroAndNegativeDurationsArePermanent(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            List<Long> permanent = List.of(0L, -1L, -DAY, Long.MIN_VALUE);
            for (int index = 0; index < permanent.size(); index++) {
                long duration = permanent.get(index);
                String issuedAs = "ISSUED-" + index;
                String changedTo = "CHANGED-" + index;
                store.issue(player, draft(issuedAs, BAN, duration, NOW, null), ResolvedCategory.BAN);
                store.issue(player, draft(changedTo, BAN, HOUR, NOW, null), ResolvedCategory.BAN);
                store.punishments().appendModification(changedTo, 0, change("M" + index, MANUAL_CHANGE, NOW, duration),
                    PunishmentPatch.builder().duration(duration).build(), List.of());

                for (String id : List.of(issuedAs, changedTo)) {
                    assertThat(store.activeUntil(id)).isEqualTo(PunishmentState.MAX);
                    assertThat(store.punishment(id).data().duration()).isEqualTo(duration);
                    assertThat(PunishmentState.displayExpiry(store.punishment(id), NOW)).isNull();
                }
            }
        }
    }

    @SqlContractTest
    void hugeDurationsSaturateInsteadOfWrapping(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            store.issue(player, draft("ISSUED", BAN, Long.MAX_VALUE, NOW, null), ResolvedCategory.BAN);
            store.issue(player, draft("CHANGED", BAN, HOUR, NOW, null), ResolvedCategory.BAN);
            store.issue(player, draft("STARTED", BAN, Long.MAX_VALUE, null, null), ResolvedCategory.BAN);
            store.punishments().appendModification("CHANGED", 0, change("M1", MANUAL_CHANGE, NOW, Long.MAX_VALUE),
                PunishmentPatch.NONE, List.of());
            store.punishments().markStarted("STARTED", NOW);

            for (String id : List.of("ISSUED", "CHANGED", "STARTED")) {
                assertThat(store.activeUntil(id)).isEqualTo(PunishmentState.MAX);
                Instant shown = PunishmentState.displayExpiry(store.punishment(id), NOW);
                assertThat(shown).isEqualTo(PunishmentState.MAX);
                assertThat(ProtoMapperSupport.toTimestamp(shown).getSeconds()).isEqualTo(PunishmentState.MAX.getEpochSecond());
            }
        }
    }

    @SqlContractTest
    void bothSentinelsRoundTrip(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            store.issue(player, draft("KICKKKKK", KICK, null, null, null), ResolvedCategory.NONE);
            store.issue(player, draft("FOREVERR", BAN, null, null, null), ResolvedCategory.BAN);

            assertThat(store.activeUntil("KICKKKKK")).isEqualTo(PunishmentState.INACTIVE);
            assertThat(store.activeUntil("FOREVERR")).isEqualTo(PunishmentState.MAX);
        }
    }

    @SqlContractTest
    void startingDerivesTheExpiryAndClearsTheStatus(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            Instant startedAt = NOW.plusSeconds(30);
            store.issue(player, draft("ACKED", BAN, HOUR, null, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);
            store.issue(player, draft("OFFENSE", BAN, HOUR, null, "habitual"), ResolvedCategory.BAN);

            assertThat(store.punishments().markStarted("ACKED", startedAt)).isTrue();
            assertThat(store.punishments().markStarted("OFFENSE", startedAt)).isTrue();

            assertThat(store.punishment("ACKED").data().status()).isNull();
            assertThat(store.activeUntil("ACKED")).isEqualTo(startedAt.plusMillis(HOUR));
            assertThat(store.punishment("OFFENSE").data().status()).isNull();
            assertThat(store.version("ACKED")).isEqualTo(1);

            assertThat(store.punishments().markStarted("ACKED", startedAt.plusSeconds(99))).isFalse();
            assertThat(store.punishments().markStarted("MISSING", startedAt)).isFalse();
            assertThat(store.punishment("ACKED").getStarted()).isEqualTo(startedAt);
        }
    }

    @SqlContractTest
    void promotionDerivesFromTheStoredRow(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            store.issue(player, draft("QUEUED", BAN, HOUR, null, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);
            store.issue(player, draft("QUEUEDST", BAN, HOUR, NOW, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);

            assertThat(store.punishments().promote("QUEUED")).isTrue();
            assertThat(store.punishments().promote("QUEUEDST")).isTrue();

            assertThat(store.activeUntil("QUEUED")).isEqualTo(PunishmentState.MAX);
            assertThat(store.activeUntil("QUEUEDST")).isEqualTo(NOW.plusMillis(HOUR));
            assertThat(store.punishments().promote("QUEUED")).isFalse();
            assertThat(store.punishments().promote("MISSING")).isFalse();
        }
    }

    @SqlContractTest
    void everyPardonAndARollbackEndThePunishmentAndOtherModificationsDoNot(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            for (PunishmentModificationType type : PunishmentModificationType.values()) {
                if (type.isDurationChange()) {
                    continue;
                }
                String id = "MOD-" + type;
                store.issue(player, draft(id, BAN, DAY, NOW, null), ResolvedCategory.BAN);
                store.punishments().appendModification(id, 0, change("M", type.name(), NOW.plusSeconds(2), null),
                    PunishmentPatch.NONE, List.of(new PunishmentNote("N", "note", NOW, "Mod", null)));

                assertThat(store.activeUntil(id)).as(type.name())
                    .isEqualTo(type.endsEnforcement() ? PunishmentState.INACTIVE : NOW.plusMillis(DAY));
                assertThat(store.modifiedAt(id)).isEqualTo(NOW.plusSeconds(2));
                assertThat(store.punishment(id).getNotes()).hasSize(1);
            }
        }
    }

    @SqlContractTest
    void theLatestDurationChangeDecidesBeforeAndAfterTheStart(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            Instant first = NOW.plusSeconds(10);
            Instant second = NOW.plusSeconds(20);
            store.issue(player, draft("BEFORE", BAN, DAY, null, null), ResolvedCategory.BAN);
            store.issue(player, draft("AFTER", BAN, DAY, NOW, null), ResolvedCategory.BAN);

            store.punishments().appendModification("BEFORE", 0, change("M1", MANUAL_CHANGE, first, HOUR),
                PunishmentPatch.builder().duration(HOUR).build(), List.of());
            assertThat(store.activeUntil("BEFORE")).isEqualTo(PunishmentState.MAX);
            store.punishments().markStarted("BEFORE", second);
            assertThat(store.activeUntil("BEFORE")).isEqualTo(second.plusMillis(HOUR));

            store.punishments().appendModification("AFTER", 0, change("M1", MANUAL_CHANGE, first, null),
                PunishmentPatch.NONE, List.of());
            assertThat(store.activeUntil("AFTER")).isEqualTo(PunishmentState.MAX);
            store.punishments().appendModification("AFTER", 1,
                change("M2", PunishmentModificationType.APPEAL_DURATION_CHANGE.name(), second, 2 * HOUR),
                PunishmentPatch.NONE, List.of());
            assertThat(store.activeUntil("AFTER")).isEqualTo(second.plusMillis(2 * HOUR));
            assertThat(store.modifiedAt("AFTER")).isEqualTo(second);
        }
    }

    @SqlContractTest
    void aPatchChangesOnlyItsFieldsAndRederives(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            store.issue(player, draft("PATCHED", BAN, HOUR, NOW, "low"), ResolvedCategory.BAN);

            store.punishments().patch("PATCHED", 0, PunishmentPatch.builder().duration(DAY).altBlocking(true).build());
            assertThat(store.activeUntil("PATCHED")).isEqualTo(NOW.plusMillis(DAY));
            assertThat(store.punishment("PATCHED").data().altBlocking()).isTrue();
            assertThat(store.punishment("PATCHED").data().status()).isEqualTo("low");

            store.punishments().patch("PATCHED", 1, PunishmentPatch.builder()
                .status(PunishmentStatus.UNSTARTED)
                .wipeAfterExpiry(true)
                .statWipeCompletedAt(NOW.plusSeconds(1))
                .appealOutcome("Approved")
                .appealTicketId("APPEAL-9")
                .build());
            Punishment patched = store.punishment("PATCHED");
            assertThat(store.activeUntil("PATCHED")).isEqualTo(PunishmentState.INACTIVE);
            assertThat(patched.data().altBlocking()).isTrue();
            assertThat(patched.data().wipeAfterExpiry()).isTrue();
            assertThat(patched.data().statWipeCompletedAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(patched.data().appealOutcome()).isEqualTo("Approved");
            assertThat(patched.data().appealTicketId()).isEqualTo("APPEAL-9");
        }
    }

    @SqlContractTest
    void aStaleVersionWritesNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            store.issue(player, draft("VERSIONS", BAN, HOUR, NOW, null), ResolvedCategory.BAN);

            assertThatThrownBy(() -> store.punishments().appendModification("VERSIONS", 7,
                change("M1", MANUAL_PARDON, NOW, null), PunishmentPatch.builder().status("Pardoned").build(), List.of()))
                .isInstanceOf(ConcurrentUpdateException.class);
            assertThatThrownBy(() -> store.punishments().patch("VERSIONS", 7, PunishmentPatch.NONE))
                .isInstanceOf(ConcurrentUpdateException.class);

            assertThat(store.punishment("VERSIONS").getModifications()).isEmpty();
            assertThat(store.punishment("VERSIONS").data().status()).isNull();
            assertThat(store.version("VERSIONS")).isZero();
        }
    }

    @SqlContractTest
    void aRetriedUnitAppendsEachChildOnce(DialectTarget target) {
        RetryInjection retries = new RetryInjection();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, retries)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            store.issue(player, draft("RETRIED", BAN, HOUR, NOW, null), ResolvedCategory.BAN);

            store.punishments().appendModification("RETRIED", 0, change("M1", MANUAL_PARDON, NOW, null),
                PunishmentPatch.NONE, List.of(new PunishmentNote("N1", "pardoned punishment", NOW, "Mod", null)));

            assertThat(retries.injected()).isPositive();
            assertThat(store.punishment("RETRIED").getModifications()).hasSize(1);
            assertThat(store.punishment("RETRIED").getNotes()).hasSize(1);
            assertThat(store.version("RETRIED")).isEqualTo(1);
        }
    }

    @SqlContractTest
    void notesEvidenceAndTicketsAppendInOrder(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            store.issue(player, draft("CHILDREN", BAN, null, null, null), ResolvedCategory.BAN);
            PunishmentEvidence text = new PunishmentEvidence("chat log", null, "text", "Mod", null, NOW, null, null, null);
            PunishmentEvidence link = new PunishmentEvidence(null, "https://example.com", "url", "Mod", null, NOW, null, null, null);

            store.punishments().appendNote("CHILDREN", new PunishmentNote("N1", "one", NOW, "Mod", null));
            store.punishments().appendEvidence("CHILDREN", List.of(text, link), new PunishmentNote("N2", "two", NOW, "Mod", null));
            store.punishments().attachTicket("CHILDREN", "T-1", new PunishmentNote("N3", "three", NOW, "Mod", null));
            store.punishments().attachTicket("CHILDREN", "T-1", new PunishmentNote("N4", "four", NOW, "Mod", null));

            Punishment read = store.punishment("CHILDREN");
            assertThat(read.getNotes()).extracting(PunishmentNote::text).containsExactly("one", "two", "three", "four");
            assertThat(read.getEvidence()).containsExactly(text, link);
            assertThat(read.getAttachedTicketIds()).containsExactly("T-1");

            store.punishments().replaceTickets("CHILDREN", List.of("T-9", "T-3", "T-9"));
            assertThat(store.punishment("CHILDREN").getAttachedTicketIds()).containsExactly("T-3", "T-9");
            store.punishments().replaceTickets("CHILDREN", List.of());
            assertThat(store.punishment("CHILDREN").getAttachedTicketIds()).isEmpty();

            assertThatThrownBy(() -> store.punishments().appendNote("MISSING", new PunishmentNote("N5", "five", NOW, "Mod", null)))
                .isInstanceOf(ReferenceViolationException.class);
        }
    }

    @SqlContractTest
    void concurrentAppendsGetDistinctSequencesWithoutRetries(DialectTarget target) throws Exception {
        AtomicInteger highestAttempt = new AtomicInteger();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory,
            attempt -> highestAttempt.accumulateAndGet(attempt, Math::max))) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            store.issue(player, draft("BUSYBUSY", BAN, null, null, null), ResolvedCategory.BAN);
            int writers = 8;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> appended = new ArrayList<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(writers)) {
                for (int writer = 0; writer < writers; writer++) {
                    PunishmentNote note = new PunishmentNote("N" + writer, "note " + writer, NOW, "Mod", null);
                    appended.add(executor.submit(() -> {
                        start.await();
                        store.punishments().appendNote("BUSYBUSY", note);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : appended) {
                    future.get();
                }
            }

            List<Integer> sequences = database.dsl().select(PUNISHMENT_NOTES.SEQ).from(PUNISHMENT_NOTES)
                .fetch(PUNISHMENT_NOTES.SEQ);
            assertThat(new TreeSet<>(sequences)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
            assertThat(store.version("BUSYBUSY")).isEqualTo(writers);
            assertThat(highestAttempt.get()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void anUnstartedTemporaryBanStaysEnforcedUntilItStarts(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("offline");
            Punishment ban = draft("X1BANBAN", BAN, DAY, null, null);
            ban.getAttachedTicketIds().add("REPORT-1");
            store.issue(player, ban, ResolvedCategory.BAN);

            Instant returns = NOW.plusMillis(2 * DAY);
            assertEnforced(store, player, returns, true);

            assertThat(store.punishments().markStarted("X1BANBAN", returns)).isTrue();
            Instant expires = NOW.plusMillis(3 * DAY);
            assertThat(store.activeUntil("X1BANBAN")).isEqualTo(expires);
            assertEnforced(store, player, returns, true);
            assertEnforced(store, player, expires, true);
            assertEnforced(store, player, expires.plusMillis(1), false);
        }
    }

    @SqlContractTest
    void anUnstartedTemporaryMuteStaysInSyncUntilItStarts(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("offline");
            store.issue(player, draft("X1MUTEEE", MUTE, DAY, null, null), ResolvedCategory.MUTE);

            Instant returns = NOW.plusMillis(2 * DAY);
            assertThat(syncedIds(store, player, returns)).containsExactly("X1MUTEEE");
            assertThat(store.punishments().countActiveByCategory(returns)).containsEntry(ResolvedCategory.MUTE, 1L);

            store.punishments().markStarted("X1MUTEEE", returns);
            Instant expires = NOW.plusMillis(3 * DAY);
            assertThat(syncedIds(store, player, expires)).containsExactly("X1MUTEEE");
            assertThat(syncedIds(store, player, expires.plusMillis(1))).isEmpty();
        }
    }

    @SqlContractTest
    void theDerivedColumnEqualsTheJavaPredicate(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            Random random = new Random(RANDOM_SEED);
            database.unitOfWork().write(() -> {
                for (int index = 0; index < GENERATED_PUNISHMENTS; index++) {
                    store.issue(player, generated(random, "G" + index), ResolvedCategory.BAN);
                }
            });

            List<Punishment> stored = store.punishments().findByPlayer(player);
            assertThat(stored).hasSize(GENERATED_PUNISHMENTS);
            assertDerivedEqualsPredicate(database, stored);
        }
    }

    @SqlContractTest
    void everyMutationPathKeepsTheColumnEqualToThePredicate(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            Random random = new Random(RANDOM_SEED);
            database.unitOfWork().write(() -> {
                for (int index = 0; index < GENERATED_PUNISHMENTS; index++) {
                    String id = "G" + index;
                    store.issue(player, generated(random, id), ResolvedCategory.BAN);
                    for (int step = random.nextInt(4); step > 0; step--) {
                        mutate(store, random, id, step);
                    }
                }
            });

            assertDerivedEqualsPredicate(database, store.punishments().findByPlayer(player));
        }
    }

    @SqlContractTest
    void hotPathFiltersAreSupersetsOfWhatJavaEnforces(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            Instant then = NOW.plusMillis(2 * DAY);
            store.issue(player, draft("ACTIVE", BAN, null, NOW, null), ResolvedCategory.BAN);
            store.issue(player, draft("QUEUED", BAN, HOUR, null, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);
            store.issue(player, draft("QUEUEDST", BAN, HOUR, NOW, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);
            store.issue(player, draft("KICK", KICK, null, null, null), ResolvedCategory.NONE);
            store.issue(player, draft("DELIVERED", KICK, null, NOW, null), ResolvedCategory.NONE);
            store.issue(player, draft("ENDED", BAN, HOUR, NOW, null), ResolvedCategory.BAN);
            Punishment wipe = draft("WIPE", BAN, HOUR, NOW, null);
            wipe.data().setWipeAfterExpiry(true);
            store.issue(player, wipe, ResolvedCategory.BAN);
            Punishment wiped = draft("WIPED", BAN, HOUR, NOW, null);
            wiped.data().setWipeAfterExpiry(true);
            wiped.data().markStatWipeCompleted(NOW);
            store.issue(player, wiped, ResolvedCategory.BAN);
            store.issue(player, draft("PARDONED", BAN, null, NOW, null), ResolvedCategory.BAN);
            store.punishments().appendModification("PARDONED", 0, change("M", MANUAL_PARDON, then.minusSeconds(5), null),
                PunishmentPatch.NONE, List.of());

            assertThat(idsOf(store.punishments().findEnforcementRelevant(player, then)))
                .containsExactlyInAnyOrder("ACTIVE", "QUEUED", "QUEUEDST", "KICK", "WIPE");
            assertThat(idsOf(store.punishments().findSyncRelevant(List.of(player), then.minusSeconds(30), then).get(player)))
                .containsExactlyInAnyOrder("ACTIVE", "QUEUED", "QUEUEDST", "KICK", "PARDONED");
            assertThat(idsOf(store.punishments().findSyncRelevant(List.of(player), NOW.minusSeconds(1), then).get(player)))
                .hasSize(9);
        }
    }

    @SqlContractTest
    void syncGroupsPunishmentsByPlayerAcrossChunks(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            List<String> players = new ArrayList<>();
            for (int index = 0; index < 1_100; index++) {
                players.add("p" + index);
            }
            store.addPlayers(players, true);
            database.unitOfWork().write(() -> {
                for (String player : players) {
                    store.issue(player, draft("B-" + player, BAN, null, null, null), ResolvedCategory.BAN);
                }
            });

            Map<String, List<Punishment>> synced = store.punishments().findSyncRelevant(players, NOW, NOW);

            assertThat(synced).hasSize(players.size());
            synced.forEach((player, punishments) -> assertThat(idsOf(punishments)).containsExactly("B-" + player));
            assertThat(store.punishments().findSyncRelevant(List.of(), NOW, NOW)).isEmpty();
        }
    }

    @SqlContractTest
    void theAltBlockingLookupReturnsTheOldestBanInForceOfALinkedAccount(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String alt = store.addPlayer("alt");
            String main = store.addPlayer("main");
            String stranger = store.addPlayer("stranger");
            store.link(alt, main);
            store.issue(main, altBlocking(draft("MUTEALT", MUTE, null, null, null), NOW.minusSeconds(50)), ResolvedCategory.MUTE);
            store.issue(main, altBlocking(draft("ENDEDALT", BAN, 1L, NOW.minusSeconds(60), null), NOW.minusSeconds(60)), ResolvedCategory.BAN);
            store.issue(main, draft("PLAINBAN", BAN, null, null, null), ResolvedCategory.BAN);
            store.issue(stranger, altBlocking(draft("OTHERALT", BAN, null, null, null), NOW.minusSeconds(90)), ResolvedCategory.BAN);

            assertThat(store.punishments().findActiveAltBlockingBansAmongLinked(alt, LINK_LIMIT, NOW)).isEmpty();

            store.issue(main, altBlocking(draft("NEWERALT", BAN, null, null, null), NOW.minusSeconds(5)), ResolvedCategory.BAN);
            store.issue(main, altBlocking(draft("OLDERALT", BAN, DAY, null, null), NOW.minusSeconds(10)), ResolvedCategory.BAN);

            List<PunishmentRecord> parents = store.punishments().findActiveAltBlockingBansAmongLinked(alt, LINK_LIMIT, NOW);
            assertThat(recordIds(parents)).containsExactly("OLDERALT", "NEWERALT");
            PunishmentRecord found = parents.getFirst();
            assertThat(found.playerMinecraftUuid()).isEqualTo(PunishmentStore.uuidOf(main).toString());
            assertThat(store.punishments().findActiveAltBlockingBansAmongLinked(main, LINK_LIMIT, NOW)).isEmpty();
        }
    }

    @SqlContractTest
    void aLinkWithoutACleanSharedAddressNeverCarriesAnAltBlockingBan(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String alt = store.addPlayer("alt");
            String main = store.addPlayer("main");
            store.linkWithoutCleanAddress(alt, main);
            store.issue(main, altBlocking(draft("ALTBAN", BAN, null, null, null), NOW.minusSeconds(5)), ResolvedCategory.BAN);

            assertThat(store.punishments().findActiveAltBlockingBansAmongLinked(alt, LINK_LIMIT, NOW)).isEmpty();
        }
    }

    @SqlContractTest
    void aLinkWhoseSharedAddressIsNoLongerCleanStopsCarryingAltBlockingBans(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String alt = store.addPlayer("alt");
            String main = store.addPlayer("main");
            String crowdedAlt = store.addPlayer("crowded-alt");
            String crowdedMain = store.addPlayer("crowded-main");
            store.link(alt, main);
            store.link(crowdedAlt, crowdedMain);
            store.issue(main, altBlocking(draft("VPNALT", BAN, null, null, null), NOW.minusSeconds(5)), ResolvedCategory.BAN);
            store.issue(crowdedMain, altBlocking(draft("CROWDALT", BAN, null, null, null), NOW.minusSeconds(5)),
                ResolvedCategory.BAN);
            assertThat(recordIds(store.punishments().findActiveAltBlockingBansAmongLinked(alt, LINK_LIMIT, NOW)))
                .containsExactly("VPNALT");
            assertThat(recordIds(store.punishments().findActiveAltBlockingBansAmongLinked(crowdedAlt, LINK_LIMIT, NOW)))
                .containsExactly("CROWDALT");

            store.flagSharedAddress(alt, main);
            store.crowdSharedAddress(crowdedAlt, crowdedMain, LINK_LIMIT - 1);

            assertThat(store.punishments().findActiveAltBlockingBansAmongLinked(alt, LINK_LIMIT, NOW)).isEmpty();
            assertThat(store.punishments().findActiveAltBlockingBansAmongLinked(crowdedAlt, LINK_LIMIT, NOW)).isEmpty();
        }
    }

    @SqlContractTest
    void replaysAreHeldByPunishmentsInForceOrWaitingToStart(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            Instant then = NOW.plusMillis(2 * DAY);
            store.issue(player, withTicket(draft("ACTIVE", BAN, null, NOW, null), "T-ACTIVE"), ResolvedCategory.BAN);
            store.issue(player, withTicket(draft("QUEUED", BAN, HOUR, null, PunishmentStatus.UNSTARTED), "T-QUEUED"), ResolvedCategory.BAN);
            store.issue(player, withTicket(draft("ENDED", BAN, HOUR, NOW, null), "T-ENDED"), ResolvedCategory.BAN);
            for (PunishmentModificationType type : PunishmentModificationType.values()) {
                if (!type.isDurationChange()) {
                    String id = "QUEUED-" + type;
                    store.issue(player, withTicket(draft(id, BAN, HOUR, null, PunishmentStatus.UNSTARTED), "T-" + type), ResolvedCategory.BAN);
                    store.punishments().appendModification(id, 0, change("M", type.name(), NOW, null), PunishmentPatch.NONE, List.of());
                }
            }

            Set<String> asked = new HashSet<>(List.of("T-ACTIVE", "T-QUEUED", "T-ENDED", "T-NOBODY"));
            Set<String> expected = new HashSet<>(List.of("T-ACTIVE", "T-QUEUED"));
            for (PunishmentModificationType type : PunishmentModificationType.values()) {
                if (!type.isDurationChange()) {
                    asked.add("T-" + type);
                    if (!type.endsEnforcement()) {
                        expected.add("T-" + type);
                    }
                }
            }
            assertThat(store.punishments().findTicketIdsHeldByActiveOrUnstarted(asked, then)).isEqualTo(expected);
            assertThat(store.punishments().findTicketIdsHeldByActiveOrUnstarted(List.of(), then)).isEmpty();
        }
    }

    @SqlContractTest
    void recentListsBreakTiesByIdAndMatchTheIssuerByNameOrId(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            store.issue(player, issued(draft("A", BAN, null, null, null), NOW, "Alice", null), ResolvedCategory.BAN);
            store.issue(player, issued(draft("B", BAN, null, null, null), NOW, null, "staff-a"), ResolvedCategory.BAN);
            store.issue(player, issued(draft("C", BAN, null, null, null), NOW, "Bob", null), ResolvedCategory.BAN);
            store.issue(player, issued(draft("D", BAN, null, null, null), NOW.minusSeconds(1), "ALICE", null), ResolvedCategory.BAN);

            assertThat(recordIds(store.punishments().findRecent(NOW.minusSeconds(1), 10))).containsExactly("C", "B", "A", "D");
            assertThat(recordIds(store.punishments().findRecent(NOW, 10))).containsExactly("C", "B", "A");
            assertThat(recordIds(store.punishments().findRecent(NOW, 2))).containsExactly("C", "B");

            IssuerMatch alice = new IssuerMatch("aLiCe", "staff-a");
            assertThat(recordIds(store.punishments().findRecentByIssuer(alice, NOW.minusSeconds(1), 10))).containsExactly("B", "A", "D");
            assertThat(recordIds(store.punishments().findRecentByIssuer(alice, NOW, 10))).containsExactly("B", "A");
            assertThat(recordIds(store.punishments().findRecentByIssuer(new IssuerMatch(null, "staff-a"), NOW, 10))).containsExactly("B");
            assertThat(store.punishments().pageIdsByIssuer(alice, null, null, null, 2)).containsExactly("A", "B");
            assertThat(store.punishments().pageIdsByIssuer(alice, null, null, "B", 2)).containsExactly("D");
            assertThat(store.punishments().pageIdsByIssuer(alice, NOW, NOW, null, 10)).containsExactly("A", "B");
        }
    }

    @SqlContractTest
    void linkedBansAndTypePagesAreOrdered(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            for (String id : List.of("L2", "L1", "L3")) {
                Punishment linked = draft(id, Punishment.LINKED_BAN_TYPE_ORDINAL, null, NOW, null);
                linked.data().setLinkedBanId(id.equals("L3") ? "OTHER" : "PARENT");
                store.issue(player, linked, ResolvedCategory.BAN);
            }
            store.issue(player, draft("M1", MUTE, null, null, null), ResolvedCategory.MUTE);

            assertThat(recordIds(store.punishments().findLinkedBans("PARENT"))).containsExactly("L1", "L2");
            assertThat(store.punishments().findLinkedBans("PARENT - PlayerName")).isEmpty();
            List<Integer> types = List.of(Punishment.LINKED_BAN_TYPE_ORDINAL, MUTE);
            assertThat(store.punishments().pageActiveIdsByTypes(types, NOW, null, 3)).containsExactly("L1", "L2", "L3");
            assertThat(store.punishments().pageActiveIdsByTypes(types, NOW, "L3", 3)).containsExactly("M1");
            assertThat(store.punishments().pageActiveIdsByTypes(List.of(), NOW, null, 3)).isEmpty();
        }
    }

    @SqlContractTest
    void countsUseTheStoredCategoryAndExpiry(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            assertThat(store.punishments().countActiveByCategory(NOW))
                .isEqualTo(Map.of(ResolvedCategory.BAN, 0L, ResolvedCategory.MUTE, 0L, ResolvedCategory.NONE, 0L));
            store.issue(player, issued(draft("B1", BAN, null, null, null), NOW, "Alice", null), ResolvedCategory.BAN);
            store.issue(player, issued(draft("B2", BAN, HOUR, NOW, null), NOW, "Alice", "staff-a"), ResolvedCategory.BAN);
            store.issue(player, issued(draft("M1", MUTE, null, null, null), NOW, null, null), ResolvedCategory.MUTE);
            store.issue(player, issued(draft("N1", 40, null, null, null), NOW, "Alice", null), ResolvedCategory.NONE);

            assertThat(store.punishments().countActiveByCategory(NOW))
                .isEqualTo(Map.of(ResolvedCategory.BAN, 2L, ResolvedCategory.MUTE, 1L, ResolvedCategory.NONE, 1L));
            assertThat(store.punishments().countActive(NOW)).isEqualTo(4);
            assertThat(store.punishments().countActive(NOW.plusMillis(2 * HOUR))).isEqualTo(3);
        }
    }

    @SqlContractTest
    void listedPagesSplitByPhaseAndWalkNewestFirst(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            for (String id : List.of("A1", "A2", "A3")) {
                store.issue(player, draft(id, BAN, null, NOW, null), ResolvedCategory.BAN);
            }
            store.issue(player, draft("E1", BAN, 1L, NOW, null), ResolvedCategory.BAN);
            store.issue(player, draft("E2", BAN, null, NOW, null), ResolvedCategory.BAN);
            store.punishments().appendModification("E2", 0, change("M", MANUAL_PARDON, NOW, null),
                PunishmentPatch.builder().status(PunishmentStatus.PARDONED).build(), List.of());
            store.issue(player, draft("E3", BAN, HOUR, null, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);
            store.punishments().appendModification("E3", 0, change("M3", MANUAL_PARDON, NOW, null),
                PunishmentPatch.builder().build(), List.of());
            store.issue(player, draft("E4", MUTE, null, null, null), ResolvedCategory.MUTE);
            store.punishments().appendModification("E4", 0, change("M4", MANUAL_PARDON, NOW, null),
                PunishmentPatch.builder().build(), List.of());
            Punishment importedPardon = draft("E5", BAN, HOUR, null, PunishmentStatus.UNSTARTED);
            importedPardon.getModifications().add(change("M5", MANUAL_PARDON, NOW, null));
            store.issue(player, importedPardon, ResolvedCategory.BAN);
            store.issue(player, draft("K1", KICK, null, NOW, null), ResolvedCategory.NONE);
            store.issue(player, draft("K2", KICK, null, null, null), ResolvedCategory.NONE);
            store.issue(player, draft("U1", BAN, HOUR, null, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);
            store.issue(player, draft("U2", MUTE, DAY, null, null), ResolvedCategory.MUTE);
            store.issue(player, draft("U3", BAN, HOUR, NOW, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);
            Instant now = NOW.plusSeconds(1);

            PunishmentListPage first = store.punishments().pageListed(newestFirst(PunishmentPhase.ACTIVE), now, null, 2);
            assertThat(recordIds(first.items())).containsExactly("A3", "A2");
            assertThat(first.next()).isEqualTo(new PunishmentListCursor(NOW.toEpochMilli(), "A2"));
            assertThat(first.items().getFirst().playerMinecraftUuid()).isEqualTo(PunishmentStore.uuidOf(player).toString());
            PunishmentListPage second = store.punishments().pageListed(newestFirst(PunishmentPhase.ACTIVE), now, first.next(), 2);
            assertThat(recordIds(second.items())).containsExactly("A1");
            assertThat(second.next()).isNull();

            PunishmentListPage ended = store.punishments().pageListed(newestFirst(PunishmentPhase.ENDED), now, null, 10);
            assertThat(recordIds(ended.items())).containsExactly("E5", "E4", "E3", "E2", "E1");
            assertThat(ended.items().getFirst().punishment().getModifications()).hasSize(1);

            PunishmentListPage unstarted = store.punishments().pageListed(newestFirst(PunishmentPhase.UNSTARTED), now, null, 10);
            assertThat(recordIds(unstarted.items())).containsExactly("U3", "U2", "U1");

            PunishmentListPage all = store.punishments().pageListed(newestFirst(null), now, null, 20);
            assertThat(recordIds(all.items())).containsExactly("U3", "U2", "U1", "E5", "E4", "E3", "E2", "E1", "A3", "A2", "A1");
            for (PunishmentPhase phase : PunishmentPhase.values()) {
                assertThat(store.punishments().pageListed(newestFirst(phase), now, null, 20).items())
                    .allSatisfy(listed -> assertThat(PunishmentPhase.of(listed.punishment(), now)).isEqualTo(phase));
            }
        }
    }

    @SqlContractTest
    void listedPagesSortAndFilterOnTheServer(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            Punishment withEvidence = issued(draft("D1", BAN, 2 * DAY, NOW.minusSeconds(30), null), NOW.minusSeconds(3), "Alice", "staff-a");
            withEvidence.getEvidence().add(new PunishmentEvidence("screenshot", "https://example.com/e.png", "file", null, "staff-a",
                NOW, null, null, null));
            store.issue(player, withEvidence, ResolvedCategory.BAN);
            store.issue(player, issued(draft("D2", MUTE, null, NOW.minusSeconds(10), null), NOW.minusSeconds(2), "Bob", null),
                ResolvedCategory.MUTE);
            store.issue(player, issued(draft("D3", BAN, DAY, NOW.minusSeconds(20), null), NOW.minusSeconds(1), "Bob", null),
                ResolvedCategory.BAN);

            assertThat(listed(store, sorted(PunishmentListQuery.SortKey.DURATION, true))).containsExactly("D3", "D1", "D2");
            assertThat(listed(store, sorted(PunishmentListQuery.SortKey.DURATION, false))).containsExactly("D2", "D1", "D3");
            assertThat(listed(store, sorted(PunishmentListQuery.SortKey.STARTED, true))).containsExactly("D1", "D3", "D2");
            assertThat(listed(store, sorted(PunishmentListQuery.SortKey.ISSUED, true))).containsExactly("D1", "D2", "D3");
            assertThat(listed(store, filtered(new IssuerMatch("alice", "staff-a"), null, PunishmentListQuery.Evidence.ANY)))
                .containsExactly("D1");
            assertThat(listed(store, filtered(null, MUTE, PunishmentListQuery.Evidence.ANY))).containsExactly("D2");
            assertThat(listed(store, filtered(null, null, PunishmentListQuery.Evidence.WITH))).containsExactly("D1");
            assertThat(listed(store, filtered(null, null, PunishmentListQuery.Evidence.WITHOUT))).containsExactly("D3", "D2");

            assertThat(walk(store, sorted(PunishmentListQuery.SortKey.DURATION, true))).containsExactly("D3", "D1", "D2");

            assertThat(store.punishments().countActiveByType(NOW)).containsExactly(Map.entry(MUTE, 1L), Map.entry(BAN, 2L));
        }
    }

    @SqlContractTest
    void issuersAreCountedByStoredKindIdAndNameWithinTheWindow(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            store.issue(player, issuedAs(draft("S1", BAN, null, NOW, null), "Alice", "staff-a", PunishmentIssuerKind.STAFF),
                ResolvedCategory.BAN);
            store.issue(player, issuedAs(draft("S2", BAN, null, NOW, null), "Alice", "staff-a", PunishmentIssuerKind.STAFF),
                ResolvedCategory.BAN);
            store.issue(player, issuedAs(draft("C1", MUTE, null, NOW, null), "Console", null, PunishmentIssuerKind.CONSOLE),
                ResolvedCategory.MUTE);
            store.issue(player, issuedAs(draft("A1", MUTE, null, NOW, null), "AI Moderator", null, PunishmentIssuerKind.AUTOMATED),
                ResolvedCategory.MUTE);
            Punishment old = issuedAs(draft("O1", MUTE, null, NOW, null), "Alice", "staff-a", PunishmentIssuerKind.STAFF);
            old.setIssued(NOW.minusSeconds(60));
            store.issue(player, old, ResolvedCategory.MUTE);

            assertThat(store.punishment("C1").getIssuerKind()).isEqualTo(PunishmentIssuerKind.CONSOLE);
            assertThat(store.punishments().countByIssuer(NOW)).containsExactly(
                new IssuerCount(PunishmentIssuerKind.STAFF, "staff-a", "Alice", 2),
                new IssuerCount(PunishmentIssuerKind.AUTOMATED, null, "AI Moderator", 1),
                new IssuerCount(PunishmentIssuerKind.CONSOLE, null, "Console", 1));
        }
    }

    @SqlContractTest
    void tiedSentinelKeysAreWalkedOnceInBothDirections(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            for (String id : List.of("P1", "P2", "P3")) {
                store.issue(player, draft(id, BAN, null, NOW, null), ResolvedCategory.BAN);
            }
            store.issue(player, draft("T1", BAN, DAY, NOW, null), ResolvedCategory.BAN);

            for (PunishmentListQuery.SortKey sortKey : List.of(PunishmentListQuery.SortKey.DURATION, PunishmentListQuery.SortKey.STARTED)) {
                assertThat(walk(store, sorted(sortKey, false))).as(sortKey + " descending")
                    .containsExactlyElementsOf(sortKey == PunishmentListQuery.SortKey.DURATION
                        ? List.of("P3", "P2", "P1", "T1") : List.of("T1", "P3", "P2", "P1"));
                assertThat(walk(store, sorted(sortKey, true))).as(sortKey + " ascending")
                    .containsExactlyElementsOf(sortKey == PunishmentListQuery.SortKey.DURATION
                        ? List.of("T1", "P1", "P2", "P3") : List.of("P1", "P2", "P3", "T1"));
            }
        }
    }

    @SqlContractTest
    void limitsComeFromTheSchemaInCodePoints(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentStore store = PunishmentStore.on(database);
            String player = store.addPlayer("p1");
            String widestName = "\ud83d\ude00".repeat(TextLimits.limit(PUNISHMENTS.ISSUER_NAME));
            store.issue(player, issued(draft("WIDE", BAN, null, null, null), NOW, widestName, null), ResolvedCategory.BAN);
            assertThat(store.punishment("WIDE").getIssuerName()).isEqualTo(widestName);

            Punishment tooWide = issued(draft("WIDER", BAN, null, null, null), NOW, widestName + "x", null);
            assertThatThrownBy(() -> store.issue(player, tooWide, ResolvedCategory.BAN)).isInstanceOf(ValueTooLongException.class);

            String unstorableId = "x".repeat(TextLimits.limit(PUNISHMENTS.ID) + 1);
            assertThat(store.punishments().findById(unstorableId)).isEmpty();
            assertThat(store.punishments().findLinkedBans(unstorableId)).isEmpty();
        }
    }

    private static void assertEnforced(PunishmentStore store, String player, Instant now, boolean enforced) {
        List<Punishment> loaded = store.punishments().findEnforcementRelevant(player, now);
        PlayerStatusCalculator calculator = PunishmentStore.calculatorAt(now);
        assertThat(loaded.stream().anyMatch(calculator::isPunishmentActive)).isEqualTo(enforced);
        assertThat(store.punishments().countActiveByCategory(now)).containsEntry(ResolvedCategory.BAN, enforced ? 1L : 0L);
        assertThat(store.players().findActiveBanHolders(List.of(player), now)).hasSize(enforced ? 1 : 0);
        assertThat(store.punishments().findTicketIdsHeldByActiveOrUnstarted(List.of("REPORT-1"), now)).hasSize(enforced ? 1 : 0);
        assertThat(store.punishments().countActive(now)).isEqualTo(enforced ? 1 : 0);
    }

    private static List<String> syncedIds(PunishmentStore store, String player, Instant now) {
        List<Punishment> synced = store.punishments().findSyncRelevant(List.of(player), now, now).getOrDefault(player, List.of());
        return idsOf(synced.stream().filter(PunishmentStore.calculatorAt(now)::isPunishmentActive).toList());
    }

    private static void assertDerivedEqualsPredicate(SqlTestDatabase database, List<Punishment> stored) {
        Map<String, Instant> activeUntil = database.dsl().select(PUNISHMENTS.ID, PUNISHMENTS.ACTIVE_UNTIL_AT)
            .from(PUNISHMENTS)
            .fetchMap(PUNISHMENTS.ID, PUNISHMENTS.ACTIVE_UNTIL_AT);
        Set<Instant> clocks = new TreeSet<>(List.of(NOW.minusMillis(DAY), NOW, NOW.plusMillis(30 * DAY), PunishmentState.MAX));
        for (Instant boundary : new TreeSet<>(activeUntil.values())) {
            if (boundary.isAfter(PunishmentState.INACTIVE) && boundary.isBefore(PunishmentState.MAX)) {
                clocks.addAll(List.of(boundary.minusMillis(1), boundary, boundary.plusMillis(1)));
            }
        }
        for (Instant now : clocks) {
            PlayerStatusCalculator calculator = PunishmentStore.calculatorAt(now);
            for (Punishment punishment : stored) {
                assertThat(!activeUntil.get(punishment.getId()).isBefore(now))
                    .as("%s at %s", punishment, now)
                    .isEqualTo(calculator.isPunishmentActive(punishment));
            }
        }
    }

    private static Punishment generated(Random random, String id) {
        Punishment punishment = draft(id, random.nextInt(5) == 0 ? KICK : BAN, generatedDuration(random),
            random.nextBoolean() ? NOW.minusMillis(random.nextLong(2 * DAY)) : null,
            random.nextInt(5) == 0 ? PunishmentStatus.UNSTARTED : null);
        int changes = random.nextInt(4);
        for (int index = 0; index < changes; index++) {
            String type = random.nextBoolean() ? MANUAL_CHANGE : PunishmentModificationType.APPEAL_DURATION_CHANGE.name();
            punishment.getModifications().add(
                change("M" + index, type, NOW.plusMillis(random.nextLong(DAY)), generatedDuration(random)));
        }
        switch (random.nextInt(9)) {
            case 0 -> punishment.getModifications().add(change("P", MANUAL_PARDON, NOW, null));
            case 1 -> punishment.getModifications().add(change("R", PunishmentModificationType.ROLLBACK.name(), NOW, null));
            case 2 -> punishment.getModifications().add(change("W", PunishmentModificationType.APPEAL_REJECT.name(), NOW, null));
            default -> {
            }
        }
        return punishment;
    }

    @Nullable
    private static Long generatedDuration(Random random) {
        return switch (random.nextInt(7)) {
            case 0 -> null;
            case 1 -> 0L;
            case 2 -> -1L;
            case 3 -> Long.MAX_VALUE;
            case 4 -> PunishmentState.MAX.toEpochMilli() - NOW.toEpochMilli();
            case 5 -> HOUR;
            default -> random.nextLong(1, 3 * DAY);
        };
    }

    private static void mutate(PunishmentStore store, Random random, String id, int step) {
        Instant at = NOW.plusMillis(random.nextLong(DAY));
        switch (random.nextInt(7)) {
            case 0 -> store.punishments().markStarted(id, at);
            case 1 -> store.punishments().promote(id);
            case 2 -> store.punishments().patch(id, store.version(id),
                PunishmentPatch.builder().status(random.nextBoolean() ? PunishmentStatus.UNSTARTED : "low").build());
            case 3 -> store.punishments().patch(id, store.version(id),
                PunishmentPatch.builder().duration(generatedDuration(random)).build());
            case 4 -> store.punishments().appendModification(id, store.version(id),
                change("S" + step, MANUAL_CHANGE, at, generatedDuration(random)), PunishmentPatch.NONE, List.of());
            case 5 -> store.punishments().appendModification(id, store.version(id),
                change("S" + step, PunishmentModificationType.ROLLBACK.name(), at, null), PunishmentPatch.NONE, List.of());
            default -> store.punishments().appendModification(id, store.version(id),
                change("S" + step, PunishmentModificationType.APPEAL_REJECT.name(), at, null), PunishmentPatch.NONE, List.of());
        }
    }

    private static Punishment altBlocking(Punishment punishment, Instant issuedAt) {
        punishment.setIssued(issuedAt);
        punishment.data().setAltBlocking(true);
        return punishment;
    }

    private static Punishment withTicket(Punishment punishment, String ticketId) {
        punishment.getAttachedTicketIds().add(ticketId);
        return punishment;
    }

    private static Punishment issued(Punishment punishment, Instant at, @Nullable String issuerName, @Nullable String issuerId) {
        punishment.setIssued(at);
        punishment.setIssuerName(issuerName);
        punishment.setIssuerId(issuerId);
        return punishment;
    }

    private static PunishmentsRecord rawPunishment(String id, String playerId) {
        PunishmentsRecord row = new PunishmentsRecord();
        row.setId(id);
        row.setPlayerId(playerId);
        row.setTypeOrdinal(BAN);
        row.setIssuedAt(NOW);
        return row;
    }

    private static List<String> idsOf(List<Punishment> punishments) {
        return punishments.stream().map(Punishment::getId).toList();
    }

    private static Punishment issuedAs(Punishment punishment, String issuerName, @Nullable String issuerId,
                                       PunishmentIssuerKind kind) {
        Punishment issuedBy = issued(punishment, NOW, issuerName, issuerId);
        issuedBy.setIssuerKind(kind);
        return issuedBy;
    }

    private static PunishmentListQuery newestFirst(@Nullable PunishmentPhase phase) {
        return new PunishmentListQuery(phase, PunishmentListQuery.SortKey.ISSUED, false, null, null, PunishmentListQuery.Evidence.ANY);
    }

    private static PunishmentListQuery sorted(PunishmentListQuery.SortKey sortKey, boolean ascending) {
        return new PunishmentListQuery(PunishmentPhase.ACTIVE, sortKey, ascending, null, null, PunishmentListQuery.Evidence.ANY);
    }

    private static PunishmentListQuery filtered(@Nullable IssuerMatch issuer, @Nullable Integer typeOrdinal,
                                                PunishmentListQuery.Evidence evidence) {
        return new PunishmentListQuery(PunishmentPhase.ACTIVE, PunishmentListQuery.SortKey.ISSUED, false, issuer,
            typeOrdinal, evidence);
    }

    private static List<String> walk(PunishmentStore store, PunishmentListQuery query) {
        List<String> walked = new ArrayList<>();
        PunishmentListCursor cursor = null;
        do {
            PunishmentListPage page = store.punishments().pageListed(query, NOW, cursor, 1);
            walked.addAll(recordIds(page.items()));
            cursor = page.next();
        } while (cursor != null);
        return walked;
    }

    private static List<String> listed(PunishmentStore store, PunishmentListQuery query) {
        return recordIds(store.punishments().pageListed(query, NOW, null, 10).items());
    }

    private static List<String> recordIds(List<PunishmentRecord> records) {
        return records.stream().map(found -> found.punishment().getId()).toList();
    }

    private static Punishment linkedBan(String id, String parentId) {
        Punishment linked = draft(id, Punishment.LINKED_BAN_TYPE_ORDINAL, HOUR, NOW, null);
        linked.data().setLinkedBanId(parentId);
        return linked;
    }
}
