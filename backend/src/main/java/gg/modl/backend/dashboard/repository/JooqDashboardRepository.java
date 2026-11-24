package gg.modl.backend.dashboard.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_ASSIGNEES;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.repository.TicketSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqDashboardRepository implements DashboardRepository {
    static final int ACTIVITY_REPLY_LIMIT = 500;

    private static final List<String> UNRESOLVED_STATUSES = List.of(TicketStatus.OPEN.getId(), TicketStatus.UNFINISHED.getId());
    private static final List<String> NON_REPORT_CATEGORIES =
        List.of(TicketCategory.SUPPORT.getId(), TicketCategory.BUG.getId(), TicketCategory.APPEAL.getId());

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final TicketRepository ticketRepository;

    @Override
    public long countPlayers() {
        return countInReport(PLAYERS, DSL.noCondition());
    }

    @Override
    public long countOnlinePlayers() {
        return dsl.fetchCount(PLAYERS, PLAYERS.IS_ONLINE.eq(true));
    }

    @Override
    public long countOnlineAmong(Collection<String> uuids) {
        return InClauseChunks.sum(uuids,
            chunk -> dsl.fetchCount(PLAYERS, PLAYERS.MINECRAFT_UUID.in(chunk).and(PLAYERS.IS_ONLINE.eq(true))));
    }

    @Override
    public long countPlayersFirstJoinedAfter(Instant after) {
        return countInReport(PLAYERS, PLAYERS.FIRST_JOIN_AT.ge(after));
    }

    @Override
    public long countPlayersFirstJoinedBetween(Instant from, Instant to) {
        return countInReport(PLAYERS, PLAYERS.FIRST_JOIN_AT.ge(from).and(PLAYERS.FIRST_JOIN_AT.lt(to)));
    }

    @Override
    public long countPunishments() {
        return countInReport(PUNISHMENTS, DSL.noCondition());
    }

    @Override
    public long countTickets() {
        return countInReport(TICKETS, DSL.noCondition());
    }

    @Override
    public long countTicketsByStatus(TicketStatus status) {
        return countInReport(TICKETS, TICKETS.STATUS.eq(status.getId()));
    }

    @Override
    public long countTicketsCreatedAfter(Instant after) {
        return countInReport(TICKETS, TICKETS.CREATED_AT.ge(after));
    }

    @Override
    public long countTicketsCreatedBetween(Instant from, Instant to) {
        return countInReport(TICKETS, TICKETS.CREATED_AT.ge(from).and(TICKETS.CREATED_AT.lt(to)));
    }

    @Override
    public long countUnresolvedTickets() {
        return countInReport(TICKETS, TICKETS.TYPE.in(NON_REPORT_CATEGORIES).and(TICKETS.STATUS.in(UNRESOLVED_STATUSES)));
    }

    @Override
    public long countUnresolvedReports() {
        return countInReport(TICKETS,
            TICKETS.TYPE.in(TicketCategory.reportCategoryIds()).and(TICKETS.STATUS.in(UNRESOLVED_STATUSES)));
    }

    @Override
    public List<TicketSummary> findRecentTickets(int limit) {
        List<String> ids = unitOfWork.report(() -> dsl.select(TICKETS.ID)
            .from(TICKETS)
            .where(TICKETS.STATUS.ne(TicketStatus.UNFINISHED.getId()))
            .orderBy(TICKETS.CREATED_AT.desc(), TICKETS.ID.desc())
            .limit(limit)
            .fetch(TICKETS.ID));
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, TicketSummary> summaries = ticketRepository.findSummaries(ids).stream()
            .collect(Collectors.toMap(TicketSummary::getId, Function.identity()));
        return ids.stream().map(summaries::get).filter(Objects::nonNull).toList();
    }

    @Override
    public List<Ticket> findStaffActivityTickets(StaffIdentity staff, Instant cutoff, int limit) {
        Condition involved = staffInvolved(staff);
        return unitOfWork.report(() -> {
            List<Ticket> tickets = dsl
                .select(TICKETS.ID, TICKETS.TYPE, TICKETS.SUBJECT, TICKETS.STATUS, TICKETS.CREATOR_NAME,
                    TICKETS.CREATED_AT, TICKETS.UPDATED_AT, TICKETS.CREATOR_UUID)
                .from(TICKETS)
                .where(TICKETS.UPDATED_AT.ge(cutoff))
                .and(involved)
                .orderBy(TICKETS.UPDATED_AT.desc(), TICKETS.ID.desc())
                .limit(limit)
                .fetch(row -> Ticket.builder()
                    .id(row.value1())
                    .type(TicketCategory.fromCanonicalId(row.value2()))
                    .subject(row.value3())
                    .status(TicketStatus.fromCanonicalId(row.value4()))
                    .creatorName(row.value5())
                    .created(row.value6())
                    .updatedAt(row.value7())
                    .creatorUuid(row.value8())
                    .build());
            attachRepliesAfter(tickets, cutoff);
            return tickets;
        });
    }

    private void attachRepliesAfter(List<Ticket> tickets, Instant cutoff) {
        if (tickets.isEmpty()) {
            return;
        }
        Map<String, List<TicketReply>> replies = dsl
            .select(TICKET_REPLIES.TICKET_ID, TICKET_REPLIES.AUTHOR_NAME, TICKET_REPLIES.CREATED_AT, TICKET_REPLIES.IS_STAFF)
            .from(TICKET_REPLIES)
            .where(TICKET_REPLIES.TICKET_ID.in(tickets.stream().map(Ticket::getId).toList()))
            .and(TICKET_REPLIES.CREATED_AT.gt(cutoff))
            .orderBy(TICKET_REPLIES.CREATED_AT.desc(), TICKET_REPLIES.TICKET_ID.desc(), TICKET_REPLIES.SEQ.desc())
            .limit(ACTIVITY_REPLY_LIMIT)
            .fetchGroups(TICKET_REPLIES.TICKET_ID,
                row -> TicketReply.builder().name(row.value2()).created(row.value3()).staff(row.value4()).build());
        tickets.forEach(ticket -> ticket.setReplies(new ArrayList<>(replies.getOrDefault(ticket.getId(), List.of()))));
    }

    private static Condition staffInvolved(StaffIdentity staff) {
        Condition involved = DSL.exists(DSL.selectOne()
            .from(TICKET_REPLIES)
            .where(TICKET_REPLIES.TICKET_ID.eq(TICKETS.ID))
            .and(TICKET_REPLIES.IS_STAFF.eq(true))
            .and(ColumnLookups.matches(TICKET_REPLIES.AUTHOR_NAME_LOWER, staff.username().toLowerCase(Locale.ROOT))));
        if (staff.minecraftUuid() != null) {
            involved = involved.or(ColumnLookups.matches(TICKETS.CREATOR_UUID, staff.minecraftUuid()));
        }
        if (staff.assignee() == null) {
            return involved;
        }
        return involved.or(DSL.exists(DSL.selectOne()
            .from(TICKET_ASSIGNEES)
            .where(TICKET_ASSIGNEES.TICKET_ID.eq(TICKETS.ID))
            .and(ColumnLookups.matches(TICKET_ASSIGNEES.ASSIGNEE, staff.assignee()))));
    }

    private long countInReport(Table<?> table, Condition condition) {
        return unitOfWork.report(() -> (long) dsl.fetchCount(table, condition));
    }
}
