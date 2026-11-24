package gg.modl.backend.dashboard.repository;

import static gg.modl.backend.analytics.repository.ReadModelRows.NOW;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_ASSIGNEES;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.analytics.repository.ReadModelRows;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.TicketAssigneesRecord;
import gg.modl.backend.database.jooq.tables.records.TicketsRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.JooqTicketRepository;
import gg.modl.backend.ticket.repository.TicketSummary;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jooq.TableRecord;
import org.junit.jupiter.api.io.TempDir;

class DashboardRepositoryContractTest {
    private static final Instant WEEK_AGO = NOW.minus(Duration.ofDays(7));
    private static final Instant TWO_WEEKS_AGO = NOW.minus(Duration.ofDays(14));
    private static final Instant YESTERDAY = NOW.minus(Duration.ofDays(1));
    private static final String ALICE_UUID = "11111111-2222-3333-4444-555555555555";
    private static final StaffIdentity ALICE = new StaffIdentity("Alice", "alice", ALICE_UUID);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void playerAndPunishmentCounts(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayersRecord online = ReadModelRows.player("online", WEEK_AGO);
            online.setIsOnline(true);
            ReadModelRows.insert(database,
                online,
                ReadModelRows.player("offline", TWO_WEEKS_AGO),
                ReadModelRows.player("never-joined", null),
                ReadModelRows.punishment("ended", "offline", 2, null, null, WEEK_AGO, Instant.EPOCH),
                ReadModelRows.punishment("active", "online", 18, null, null, WEEK_AGO, NOW));
            JooqDashboardRepository repository = repository(database);

            assertThat(repository.countPlayers()).isEqualTo(3);
            assertThat(repository.countOnlinePlayers()).isEqualTo(1);
            assertThat(repository.countPlayersFirstJoinedAfter(WEEK_AGO)).isEqualTo(1);
            assertThat(repository.countPlayersFirstJoinedBetween(TWO_WEEKS_AGO, WEEK_AGO)).isEqualTo(1);
            assertThat(repository.countPunishments()).isEqualTo(2);
            assertThat(repository.countOnlineAmong(List.of())).isZero();
        }
    }

    @SqlContractTest
    void onlineLookupsCrossTheInClauseChunkSize(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            List<TableRecord<?>> rows = new ArrayList<>();
            List<String> uuids = new ArrayList<>();
            for (int index = 0; index < 501; index++) {
                PlayersRecord player = ReadModelRows.player("p" + index, null);
                player.setIsOnline(true);
                rows.add(player);
                uuids.add(player.getMinecraftUuid());
            }
            ReadModelRows.insertAll(database, rows);

            assertThat(repository(database).countOnlineAmong(uuids)).isEqualTo(501);
        }
    }

    @SqlContractTest
    void ticketCountsSeparateReportsFromTickets(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.ticket("T-1", "player", "open", WEEK_AGO, NOW),
                ReadModelRows.ticket("T-2", "chat", "unfinished", TWO_WEEKS_AGO, NOW),
                ReadModelRows.ticket("T-3", "support", "open", NOW, NOW),
                ReadModelRows.ticket("T-4", "appeal", "closed", NOW, NOW),
                ReadModelRows.ticket("T-5", "application", "open", NOW, NOW));
            JooqDashboardRepository repository = repository(database);

            assertThat(repository.countTickets()).isEqualTo(5);
            assertThat(repository.countTicketsByStatus(TicketStatus.OPEN)).isEqualTo(3);
            assertThat(repository.countTicketsCreatedAfter(WEEK_AGO)).isEqualTo(4);
            assertThat(repository.countTicketsCreatedBetween(TWO_WEEKS_AGO, WEEK_AGO)).isEqualTo(1);
            assertThat(repository.countUnresolvedReports()).isEqualTo(2);
            assertThat(repository.countUnresolvedTickets()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void recentTicketsAreNewestFirstWithAnIdTieBreak(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.ticket("T-old", "support", "open", WEEK_AGO, NOW),
                ReadModelRows.ticket("T-a", "support", "open", YESTERDAY, NOW),
                ReadModelRows.ticket("T-b", "support", "closed", YESTERDAY, NOW),
                ReadModelRows.ticket("T-draft", "support", "unfinished", NOW, NOW));
            JooqDashboardRepository repository = repository(database);

            assertThat(repository.findRecentTickets(2)).extracting(TicketSummary::getId).containsExactly("T-b", "T-a");
            assertThat(repository.findRecentTickets(10)).extracting(TicketSummary::getId).containsExactly("T-b", "T-a", "T-old");
        }
    }

    @SqlContractTest
    void staffActivityFindsCreatedAssignedAndAnsweredTickets(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            TicketsRecord created = ReadModelRows.ticket("T-created", "support", "open", YESTERDAY, NOW);
            created.setCreatorUuid(ALICE_UUID);
            ReadModelRows.insert(database,
                created,
                ReadModelRows.ticket("T-assigned", "bug", "open", WEEK_AGO, NOW.minusSeconds(1)),
                ReadModelRows.ticket("T-answered", "bug", "closed", WEEK_AGO, NOW.minusSeconds(2)),
                ReadModelRows.ticket("T-unrelated", "bug", "open", WEEK_AGO, NOW),
                ReadModelRows.ticket("T-stale", "bug", "open", TWO_WEEKS_AGO, TWO_WEEKS_AGO),
                new TicketAssigneesRecord("T-assigned", "alice"),
                new TicketAssigneesRecord("T-stale", "alice"),
                ReadModelRows.reply("T-answered", 1, "ALICE", true, WEEK_AGO),
                ReadModelRows.reply("T-answered", 2, "Player", false, YESTERDAY));
            JooqDashboardRepository repository = repository(database);

            List<Ticket> tickets = repository.findStaffActivityTickets(ALICE, WEEK_AGO, 10);

            assertThat(tickets).extracting(Ticket::getId).containsExactly("T-created", "T-assigned", "T-answered");
            assertThat(tickets.getFirst().getCreatorUuid()).isEqualTo(ALICE_UUID);
            assertThat(tickets.get(2).getReplies()).singleElement().satisfies(reply -> {
                assertThat(reply.getName()).isEqualTo("Player");
                assertThat(reply.isStaff()).isFalse();
            });
            assertThat(repository.findStaffActivityTickets(new StaffIdentity("Alice", null, ALICE_UUID), WEEK_AGO, 10))
                .extracting(Ticket::getId).containsExactly("T-created", "T-answered");
            assertThat(repository.findStaffActivityTickets(ALICE, WEEK_AGO, 1))
                .extracting(Ticket::getId).containsExactly("T-created");
        }
    }

    @SqlContractTest
    void aPlayerWhoPicksTheNameOfAStaffMemberIsNotThatStaffMember(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            TicketsRecord named = ReadModelRows.ticket("T-named", "support", "open", YESTERDAY, NOW);
            named.setCreatorName("Alice");
            ReadModelRows.insert(database,
                named,
                ReadModelRows.ticket("T-replied", "bug", "open", YESTERDAY, NOW),
                ReadModelRows.reply("T-replied", 1, "Alice", false, YESTERDAY));
            JooqDashboardRepository repository = repository(database);

            assertThat(repository.findStaffActivityTickets(ALICE, WEEK_AGO, 10)).isEmpty();
            assertThat(repository.findStaffActivityTickets(new StaffIdentity("Alice", null, null), WEEK_AGO, 10)).isEmpty();
        }
    }

    @SqlContractTest
    void anOverLongStaffNameIsAnEmptyResultInsteadOfAnError(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            String longestAssignee = "a".repeat(TextLimits.limit(TICKET_ASSIGNEES.ASSIGNEE));
            String longestName = "n".repeat(TextLimits.limit(TICKET_REPLIES.AUTHOR_NAME));
            String tooLongName = "n".repeat(TextLimits.limit(TICKET_REPLIES.AUTHOR_NAME_LOWER) + 1);
            String tooLongUuid = "u".repeat(TextLimits.limit(TICKETS.CREATOR_UUID) + 1);
            ReadModelRows.insert(database,
                ReadModelRows.ticket("T-answered", "support", "open", YESTERDAY, NOW),
                ReadModelRows.reply("T-answered", 1, longestName, true, YESTERDAY),
                ReadModelRows.ticket("T-assigned", "bug", "open", YESTERDAY, YESTERDAY),
                new TicketAssigneesRecord("T-assigned", longestAssignee));
            JooqDashboardRepository repository = repository(database);

            assertThat(repository.findStaffActivityTickets(
                new StaffIdentity(tooLongName, longestAssignee + "a", tooLongUuid), WEEK_AGO, 10)).isEmpty();
            assertThat(repository.findStaffActivityTickets(new StaffIdentity(longestName, null, null), WEEK_AGO, 10))
                .extracting(Ticket::getId).containsExactly("T-answered");
            assertThat(repository.findStaffActivityTickets(new StaffIdentity(tooLongName, longestAssignee, null), WEEK_AGO, 10))
                .extracting(Ticket::getId).containsExactly("T-assigned");
        }
    }

    private static JooqDashboardRepository repository(SqlTestDatabase database) {
        return new JooqDashboardRepository(database.dsl(), database.unitOfWork(), new JooqTicketRepository(
            database.dsl(), database.unitOfWork(), database.insertIfAbsent(), database.childRows(), database.jsonColumns()));
    }
}
