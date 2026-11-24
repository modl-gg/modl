package gg.modl.backend.audit.repository;

import static gg.modl.backend.analytics.repository.ReadModelRows.NOW;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.analytics.repository.ReadModelRows;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.player.repository.JooqPunishmentRepository;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.TableRecord;
import org.junit.jupiter.api.io.TempDir;

class StaffActivityRepositoryContractTest {
    private static final Instant START = NOW.minus(Duration.ofDays(7));
    private static final Instant YESTERDAY = NOW.minus(Duration.ofDays(1));
    private static final String STAFF_ID = "staff-1";
    private static final List<String> ALICE_NAMES = List.of("ALICE", "AliceMC");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void logActivityIsSummedPerSourceWithoutTheSystem(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.log(1, "info", "alice", "Closed TICKET 4", YESTERDAY),
                ReadModelRows.log(2, "info", "alice", "Issued a BAN", NOW),
                ReadModelRows.log(3, "moderation", "alice", "Changed something", YESTERDAY),
                ReadModelRows.log(4, "info", "bob", "Signed in", YESTERDAY),
                ReadModelRows.log(5, "info", "system", "Swept", YESTERDAY),
                ReadModelRows.log(6, "info", "alice", "Old", START.minusMillis(1)));

            assertThat(repository(database).summarizeLogActivityBySource(START)).containsExactly(
                new StaffActionSummary("alice", 3, 1, 2, NOW),
                new StaffActionSummary("bob", 1, 0, 0, YESTERDAY));
        }
    }

    @SqlContractTest
    void repliesAreGroupedByTheLowerCaseAuthor(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.ticket("T-1", "support", "open", START, NOW),
                ReadModelRows.ticket("T-2", "bug", "closed", START, NOW),
                ReadModelRows.reply("T-1", 1, "Alice", true, YESTERDAY),
                ReadModelRows.reply("T-1", 2, "ALICE", true, NOW),
                ReadModelRows.reply("T-2", 1, "alice", true, YESTERDAY.plusSeconds(1)),
                ReadModelRows.reply("T-2", 2, "player", false, NOW),
                ReadModelRows.reply("T-2", 3, null, true, NOW),
                ReadModelRows.reply("T-2", 4, "Alice", true, START.minusMillis(1)));
            JooqStaffActivityRepository repository = repository(database);

            assertThat(repository.countTicketResponsesByStaff(START)).containsExactly(Map.entry("alice", 3L));
            assertThat(repository.averageResponseMinutesByStaff(START))
                .containsExactly(Map.entry("alice", (int) Duration.between(START, YESTERDAY).toMinutes()));
            assertThat(repository.findTicketDetails("aLiCe", START)).containsExactly(
                new StaffTicketDetail("T-1", null, TicketCategory.SUPPORT, TicketStatus.OPEN, START, YESTERDAY, NOW),
                new StaffTicketDetail("T-2", null, TicketCategory.BUG, TicketStatus.CLOSED, START,
                    YESTERDAY.plusSeconds(1), YESTERDAY.plusSeconds(1)));
            assertThat(repository.countTicketResponsesByDay("Alice", START))
                .containsExactly(Map.entry(YESTERDAY, 2L), Map.entry(NOW, 1L));
        }
    }

    @SqlContractTest
    void responseAveragesTruncateEachTicketsFirstReplyBeforeAveraging(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.ticket("T-1", "support", "open", YESTERDAY, NOW),
                ReadModelRows.ticket("T-2", "support", "open", YESTERDAY, NOW),
                ReadModelRows.reply("T-1", 1, "Alice", true, YESTERDAY.plusSeconds(359)),
                ReadModelRows.reply("T-1", 2, "alice", true, YESTERDAY.plusSeconds(7_200)),
                ReadModelRows.reply("T-1", 3, "Bob", true, YESTERDAY.plusSeconds(60)),
                ReadModelRows.reply("T-2", 1, "ALICE", true, YESTERDAY.plusSeconds(419)),
                ReadModelRows.reply("T-2", 2, "Player", false, YESTERDAY.plusSeconds(1)),
                ReadModelRows.reply("T-2", 3, null, true, YESTERDAY.plusSeconds(1)));

            assertThat(repository(database).averageResponseMinutesByStaff(START))
                .containsExactly(Map.entry("alice", 5), Map.entry("bob", 1));
            assertThat(repository(database).averageResponseMinutesByStaff(NOW)).isEmpty();
            assertThat(repository(database).averageResponseMinutesOf("BOB", START)).isEqualTo(1);
            assertThat(repository(database).averageResponseMinutesOf("alice", START)).isEqualTo(5);
            assertThat(repository(database).averageResponseMinutesOf("carol", START)).isZero();
        }
    }

    @SqlContractTest
    void punishmentsMatchEitherNameOrTheStaffIdExactlyOnce(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.player("p1", null),
                ReadModelRows.punishment("by-id", "p1", 18, null, STAFF_ID, YESTERDAY, NOW),
                ReadModelRows.punishment("by-name", "p1", 18, "Alice", null, YESTERDAY.plusSeconds(1), NOW),
                ReadModelRows.punishment("by-minecraft", "p1", 2, "alicemc", null, NOW, NOW),
                ReadModelRows.punishment("other", "p1", 2, "Bob", null, NOW, NOW),
                ReadModelRows.punishment("old", "p1", 2, "Alice", null, START.minusMillis(1), NOW),
                ReadModelRows.modification("by-name", 1, "ROLLBACK", NOW));
            JooqStaffActivityRepository repository = repository(database);

            List<PunishmentRecord> details = repository.findPunishmentDetails(ALICE_NAMES, STAFF_ID, START);

            assertThat(details).extracting(found -> found.punishment().getId())
                .containsExactly("by-minecraft", "by-name", "by-id");
            assertThat(details).allSatisfy(found -> assertThat(found.playerMinecraftUuid()).isEqualTo(ReadModelRows.uuidOf("p1")));
            assertThat(details.get(1).punishment().getModifications()).singleElement()
                .satisfies(modification -> assertThat(modification.type()).isEqualTo("ROLLBACK"));
            assertThat(repository.countPunishmentsByType(ALICE_NAMES, STAFF_ID, START))
                .containsExactly(Map.entry(18, 2L), Map.entry(2, 1L));
            assertThat(repository.countPunishmentsByDay(ALICE_NAMES, STAFF_ID, START))
                .containsExactly(Map.entry(YESTERDAY, 2L), Map.entry(NOW, 1L));
            assertThat(repository.countPunishmentsByType(List.of("Alice"), null, START)).containsExactly(Map.entry(18, 1L));
        }
    }

    @SqlContractTest
    void detailListsKeepOnlyTheNewestRows(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            List<TableRecord<?>> rows = new ArrayList<>(List.of(ReadModelRows.player("p1", null)));
            for (int index = 0; index <= JooqStaffActivityRepository.DETAIL_LIMIT; index++) {
                Instant at = YESTERDAY.plusSeconds(index);
                rows.add(ReadModelRows.punishment("punishment-" + index, "p1", 2, "Alice", null, at, NOW));
                rows.add(ReadModelRows.ticket("T-" + index, "support", "open", START, at));
                rows.add(ReadModelRows.reply("T-" + index, 1, "Alice", true, at));
            }
            ReadModelRows.insertAll(database, rows);
            JooqStaffActivityRepository repository = repository(database);

            assertThat(repository.findPunishmentDetails(List.of("Alice"), null, START))
                .hasSize(JooqStaffActivityRepository.DETAIL_LIMIT)
                .noneMatch(found -> found.punishment().getId().equals("punishment-0"));
            assertThat(repository.findTicketDetails("Alice", START))
                .hasSize(JooqStaffActivityRepository.DETAIL_LIMIT)
                .noneMatch(found -> found.id().equals("T-0"));
        }
    }

    @SqlContractTest
    void anOverLongLookupValueIsAnEmptyResultInsteadOfAnError(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            String longestName = "x".repeat(
                Math.min(TextLimits.limit(PUNISHMENTS.ISSUER_NAME), TextLimits.limit(TICKET_REPLIES.AUTHOR_NAME)));
            String longestId = "i".repeat(TextLimits.limit(PUNISHMENTS.ISSUER_ID));
            String tooLongName = "x".repeat(
                Math.max(TextLimits.limit(PUNISHMENTS.ISSUER_NAME_LOWER), TextLimits.limit(TICKET_REPLIES.AUTHOR_NAME_LOWER)) + 1);
            ReadModelRows.insert(database,
                ReadModelRows.player("p1", null),
                ReadModelRows.punishment("by-name", "p1", 18, longestName, null, NOW, NOW),
                ReadModelRows.punishment("by-id", "p1", 2, null, longestId, NOW, NOW),
                ReadModelRows.ticket("T-1", "support", "open", START, NOW),
                ReadModelRows.reply("T-1", 1, longestName, true, NOW));
            JooqStaffActivityRepository repository = repository(database);
            List<String> tooLongNames = List.of(tooLongName);
            String tooLongId = longestId + "i";

            assertThat(repository.findPunishmentDetails(tooLongNames, tooLongId, START)).isEmpty();
            assertThat(repository.countPunishmentsByType(tooLongNames, tooLongId, START)).isEmpty();
            assertThat(repository.countPunishmentsByDay(tooLongNames, tooLongId, START)).isEmpty();
            assertThat(repository.findTicketDetails(tooLongName, START)).isEmpty();
            assertThat(repository.countTicketResponsesByDay(tooLongName, START)).isEmpty();

            assertThat(repository.countPunishmentsByType(tooLongNames, longestId, START)).containsExactly(Map.entry(2, 1L));
            assertThat(repository.countPunishmentsByType(List.of(tooLongName, longestName), tooLongId, START))
                .containsExactly(Map.entry(18, 1L));
            assertThat(repository.findTicketDetails(longestName, START)).hasSize(1);
        }
    }

    private static JooqStaffActivityRepository repository(SqlTestDatabase database) {
        return new JooqStaffActivityRepository(database.dsl(), database.unitOfWork(), new JooqPunishmentRepository(
            database.dsl(), database.unitOfWork(), database.insertIfAbsent(), database.childRows(),
            database.jsonColumns()));
    }
}
