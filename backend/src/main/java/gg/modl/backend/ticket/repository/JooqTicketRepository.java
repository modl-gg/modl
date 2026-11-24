package gg.modl.backend.ticket.repository;

import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_ASSIGNEES;
import static gg.modl.backend.database.jooq.Tables.TICKET_NOTES;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static gg.modl.backend.database.jooq.Tables.TICKET_TAGS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.TicketReplies;
import gg.modl.backend.database.jooq.tables.records.TicketAssigneesRecord;
import gg.modl.backend.database.jooq.tables.records.TicketNotesRecord;
import gg.modl.backend.database.jooq.tables.records.TicketRepliesRecord;
import gg.modl.backend.database.jooq.tables.records.TicketTagsRecord;
import gg.modl.backend.database.jooq.tables.records.TicketsRecord;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class JooqTicketRepository implements TicketRepository {
    static final int UNPAGED_LIST_LIMIT = 500;

    private static final int MINECRAFT_LIST_LIMIT = 100;
    private static final int CREATOR_LIST_LIMIT = 50;
    private static final Comparator<TicketsRecord> CREATED_FIRST = Comparator.comparing(TicketsRecord::getCreatedAt)
        .thenComparing(TicketsRecord::getId);
    private static final Comparator<TicketsRecord> UPDATED_FIRST = Comparator.comparing(TicketsRecord::getUpdatedAt)
        .thenComparing(TicketsRecord::getId);
    private static final List<Field<?>> SUMMARY_FIELDS = List.of(
        TICKETS.ID, TICKETS.TYPE, TICKETS.SUBJECT, TICKETS.STATUS, TICKETS.PRIORITY, TICKETS.CREATOR_UUID,
        TICKETS.CREATOR_NAME, TICKETS.REPORTED_PLAYER_NAME, TICKETS.REPORTED_PLAYER_UUID, TICKETS.IS_LOCKED,
        TICKETS.IS_HIDDEN, TICKETS.APPEAL_WORKFLOW_STATUS, TICKETS.APPEAL_PUNISHMENT_ID, TICKETS.REPLAY_ID, TICKETS.PUBLIC_ACCESS_KEY,
        TICKETS.REPLY_COUNT, TICKETS.CREATED_AT, TICKETS.UPDATED_AT, TICKETS.VERSION);

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;
    private final ChildRows childRows;
    private final TicketRows rows;

    public JooqTicketRepository(DSLContext dsl, UnitOfWork unitOfWork, InsertIfAbsent insertIfAbsent, ChildRows childRows,
                                JsonColumns json) {
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
        this.insertIfAbsent = insertIfAbsent;
        this.childRows = childRows;
        this.rows = new TicketRows(json);
    }

    @Override
    public boolean insertIfAbsent(Ticket ticket) {
        return unitOfWork.write(() -> insertTicket(ticket));
    }

    private boolean insertTicket(Ticket ticket) {
        TicketsRecord header = rows.header(ticket);
        if (!insertIfAbsent.onPrimaryKey(TICKETS).insert(header)) {
            return false;
        }
        String id = header.getId();
        insertTags(id, sortedValues(TICKET_TAGS.TAG, ticket.getTags()));
        insertAssignees(id, sortedValues(TICKET_ASSIGNEES.ASSIGNEE, ticket.getAssignedTo()));
        int replySeq = 0;
        for (TicketReply reply : Objects.requireNonNullElse(ticket.getReplies(), List.<TicketReply>of())) {
            TicketRepliesRecord row = rows.reply(id, reply, ticket.getCreated());
            row.setSeq(++replySeq);
            dsl.insertInto(TICKET_REPLIES).set(row).execute();
        }
        int noteSeq = 0;
        for (TicketNote note : Objects.requireNonNullElse(ticket.getNotes(), List.<TicketNote>of())) {
            TicketNotesRecord row = rows.note(id, note, ticket.getCreated());
            row.setSeq(++noteSeq);
            dsl.insertInto(TICKET_NOTES).set(row).execute();
        }
        return true;
    }

    @Override
    public Optional<Ticket> findById(String id) {
        return unitOfWork.read(() -> threads(dsl.selectFrom(TICKETS)
            .where(ColumnLookups.matches(TICKETS.ID, id))
            .fetch()).stream().findFirst());
    }

    @Override
    public List<ListedTicket> findListedByIds(Collection<String> ids) {
        return unitOfWork.read(() -> withDigests(InClauseChunks.collect(ticketKeys(ids),
                chunk -> dsl.selectFrom(TICKETS).where(TICKETS.ID.in(chunk)).fetch())
            .stream().sorted(CREATED_FIRST).toList()));
    }

    @Override
    public List<TicketSummary> findSummaries(Collection<String> ids) {
        return unitOfWork.read(() -> summaries(InClauseChunks.collect(ticketKeys(ids),
                chunk -> dsl.select(SUMMARY_FIELDS).from(TICKETS).where(TICKETS.ID.in(chunk)).fetchInto(TICKETS))
            .stream().sorted(Comparator.comparing(TicketsRecord::getId)).toList()));
    }

    @Override
    public TicketSearchPage search(TicketSearchFilter filter, TicketSortOption sort, int page, int limit) {
        return readFiltered(filter, () -> {
            Condition matching = TicketConditions.search(filter);
            long total = dsl.selectCount().from(TICKETS).where(matching).fetchSingle().value1();
            List<TicketsRecord> found = dsl.select(SUMMARY_FIELDS).from(TICKETS)
                .where(matching)
                .orderBy(order(sort))
                .limit(limit)
                .offset(Math.max(page - 1L, 0L) * limit)
                .fetchInto(TICKETS);
            return new TicketSearchPage(summaries(found), total);
        });
    }

    @Override
    public TicketCounts count(TicketSearchFilter filter) {
        return readFiltered(filter, () -> {
            Field<Integer> tickets = DSL.count();
            Map<Boolean, Integer> byLocked = dsl.select(TICKETS.IS_LOCKED, tickets).from(TICKETS)
                .where(TicketConditions.counted(filter))
                .groupBy(TICKETS.IS_LOCKED)
                .fetchMap(TICKETS.IS_LOCKED, tickets);
            return new TicketCounts(byLocked.getOrDefault(false, 0), byLocked.getOrDefault(true, 0));
        });
    }

    @Override
    public List<Ticket> findByPlayer(String playerUuid) {
        return unitOfWork.read(() -> listed(
            involving(playerUuid, TicketConditions.notUnfinished(), UNPAGED_LIST_LIMIT)));
    }

    @Override
    public List<Ticket> findByTag(String tag) {
        return unitOfWork.read(() -> listed(dsl.selectFrom(TICKETS)
            .where(TICKETS.ID.in(DSL.select(TICKET_TAGS.TICKET_ID).from(TICKET_TAGS)
                .where(ColumnLookups.matches(TICKET_TAGS.TAG, tag))))
            .orderBy(TICKETS.CREATED_AT.desc(), TICKETS.ID.desc())
            .limit(UNPAGED_LIST_LIMIT)
            .fetch()));
    }

    @Override
    public List<Ticket> findReportedPlayerTickets(String reportedPlayerUuid, int limit) {
        return unitOfWork.read(() -> listed(dsl.selectFrom(TICKETS)
            .where(isPlayer(TICKETS.REPORTED_PLAYER_UUID, reportedPlayerUuid))
            .orderBy(TICKETS.CREATED_AT.desc(), TICKETS.ID.desc())
            .limit(limit)
            .fetch()));
    }

    @Override
    public List<ListedTicket> findMinecraftTickets(@Nullable String status, @Nullable String type, int limit) {
        return unitOfWork.read(() -> withDigests(dsl.selectFrom(TICKETS)
            .where(TicketConditions.minecraftType(type))
            .and(TicketConditions.status(status))
            .orderBy(TICKETS.CREATED_AT.desc(), TICKETS.ID.desc())
            .limit(Math.min(limit, MINECRAFT_LIST_LIMIT))
            .fetch()));
    }

    @Override
    public List<ListedTicket> findReports(@Nullable String status, @Nullable String playerUuid, int limit, boolean sortByCreatedDesc) {
        Condition reportedPlayer = playerUuid == null || playerUuid.isBlank()
            ? DSL.noCondition()
            : isPlayer(TICKETS.REPORTED_PLAYER_UUID, playerUuid);
        List<SortField<?>> order = sortByCreatedDesc
            ? List.of(TICKETS.CREATED_AT.desc(), TICKETS.ID.desc())
            : List.of(TICKETS.CREATED_AT.asc(), TICKETS.ID.asc());
        return unitOfWork.read(() -> withDigests(dsl.selectFrom(TICKETS)
            .where(TICKETS.TYPE.in(TicketCategory.reportCategoryIds()))
            .and(reportedPlayer)
            .and(TicketConditions.status(status))
            .orderBy(order)
            .limit(Math.min(limit, MINECRAFT_LIST_LIMIT))
            .fetch()));
    }

    @Override
    public List<Ticket> findRecentByCreator(String creatorUuid, int limit) {
        return unitOfWork.read(() -> listed(dsl.selectFrom(TICKETS)
            .where(isPlayer(TICKETS.CREATOR_UUID, creatorUuid))
            .orderBy(TICKETS.CREATED_AT.desc(), TICKETS.ID.desc())
            .limit(Math.min(limit, CREATOR_LIST_LIMIT))
            .fetch()));
    }

    @Override
    public List<Ticket> findPlayerTicketsWithReplayUrl(String playerUuid, int limit) {
        Condition withReplay = TICKETS.REPLAY_URL.isNotNull().and(TICKETS.REPLAY_URL.ne(""));
        return unitOfWork.read(() -> listed(involving(playerUuid, withReplay, Math.min(limit, MINECRAFT_LIST_LIMIT))));
    }

    @Override
    public List<Ticket> findAppealsByPunishmentId(String punishmentId) {
        return unitOfWork.read(() -> threads(dsl.selectFrom(TICKETS)
            .where(ColumnLookups.matches(TICKETS.APPEAL_PUNISHMENT_ID, punishmentId))
            .and(TICKETS.TYPE.eq(TicketCategory.APPEAL.getId()))
            .orderBy(TICKETS.CREATED_AT, TICKETS.ID)
            .fetch()));
    }

    @Override
    public List<TicketSummary> findAppealSummariesByPunishmentId(String punishmentId) {
        return unitOfWork.read(() -> summaries(dsl.select(SUMMARY_FIELDS).from(TICKETS)
            .where(ColumnLookups.matches(TICKETS.APPEAL_PUNISHMENT_ID, punishmentId))
            .and(TICKETS.TYPE.eq(TicketCategory.APPEAL.getId()))
            .orderBy(TICKETS.CREATED_AT, TICKETS.ID)
            .fetchInto(TICKETS)));
    }

    @Override
    public List<Ticket> findCreatedAfterExcludingUnfinished(Instant after, int limit) {
        return unitOfWork.read(() -> listed(dsl.selectFrom(TICKETS)
            .where(TICKETS.CREATED_AT.ge(after))
            .and(TicketConditions.notUnfinished())
            .orderBy(TICKETS.CREATED_AT, TICKETS.ID)
            .limit(limit)
            .fetch()));
    }

    @Override
    public List<TicketSummary> findRecentActiveWithRepliesByIds(Collection<String> ids, int limit) {
        return unitOfWork.read(() -> summaries(InClauseChunks.collect(ticketKeys(ids), chunk -> dsl.selectFrom(TICKETS)
                .where(TICKETS.ID.in(chunk))
                .and(activeWithReplies())
                .orderBy(TICKETS.UPDATED_AT.desc(), TICKETS.ID.desc())
                .limit(limit)
                .fetchInto(TICKETS))
            .stream().sorted(UPDATED_FIRST.reversed()).limit(limit).toList()));
    }

    @Override
    public List<TicketSummary> findRecentAssignedWithReplies(String assignee, int limit) {
        return unitOfWork.read(() -> summaries(dsl.select(SUMMARY_FIELDS).from(TICKETS)
            .where(TICKETS.ID.in(DSL.select(TICKET_ASSIGNEES.TICKET_ID).from(TICKET_ASSIGNEES)
                .where(ColumnLookups.matches(TICKET_ASSIGNEES.ASSIGNEE, assignee))))
            .and(activeWithReplies())
            .orderBy(TICKETS.UPDATED_AT.desc(), TICKETS.ID.desc())
            .limit(limit)
            .fetchInto(TICKETS)));
    }

    @Override
    public List<ReplyStamp> findReplyStamps(Collection<String> ticketIds, Instant after) {
        return unitOfWork.read(() -> InClauseChunks.collect(ticketKeys(ticketIds), chunk -> dsl
                .select(TICKET_REPLIES.TICKET_ID, TICKET_REPLIES.SEQ, TICKET_REPLIES.CREATED_AT, TICKET_REPLIES.IS_STAFF,
                    TICKET_REPLIES.AUTHOR_NAME)
                .from(TICKET_REPLIES)
                .where(TICKET_REPLIES.TICKET_ID.in(chunk))
                .and(TICKET_REPLIES.CREATED_AT.gt(after))
                .orderBy(TICKET_REPLIES.TICKET_ID, TICKET_REPLIES.SEQ)
                .fetch(row -> new ReplyStamp(row.value1(), row.value2(), row.value3(), row.value4(), row.value5()))));
    }

    @Override
    public Map<String, TicketReply> findReplies(Map<String, Integer> seqByTicketId) {
        List<Condition> keys = seqByTicketId.entrySet().stream()
            .map(entry -> ColumnLookups.matches(TICKET_REPLIES.TICKET_ID, entry.getKey())
                .and(TICKET_REPLIES.SEQ.eq(entry.getValue())))
            .toList();
        Map<String, TicketReply> found = new LinkedHashMap<>();
        unitOfWork.read(() -> InClauseChunks.collect(keys, chunk -> dsl.selectFrom(TICKET_REPLIES).where(DSL.or(chunk)).fetch()))
            .forEach(row -> found.put(row.getTicketId(), rows.reply(row)));
        return found;
    }

    @Override
    public void appendReply(String id, TicketReply reply, Instant now) {
        TicketRepliesRecord row = rows.reply(id, reply, now);
        unitOfWork.write(() -> {
            childRows.append(
                () -> dsl.update(TICKETS)
                    .set(TICKETS.REPLY_COUNT, TICKETS.REPLY_COUNT.plus(1))
                    .set(TICKETS.UPDATED_AT, now)
                    .where(TICKETS.ID.eq(row.getTicketId()))
                    .execute(),
                TICKET_REPLIES, TICKET_REPLIES.TICKET_ID, TICKET_REPLIES.SEQ, row);
        });
    }

    @Override
    public void appendNote(String id, TicketNote note, Instant now) {
        TicketNotesRecord row = rows.note(id, note, now);
        unitOfWork.write(() -> {
            childRows.append(() -> touch(row.getTicketId(), now),
                TICKET_NOTES, TICKET_NOTES.TICKET_ID, TICKET_NOTES.SEQ, row);
        });
    }

    @Override
    public void reassignReplies(String id, Collection<String> replyIds, String authorName, @Nullable String creatorIdentifier) {
        String name = TextLimits.require(TICKET_REPLIES.AUTHOR_NAME, authorName);
        String nameLower = TextLimits.require(TICKET_REPLIES.AUTHOR_NAME_LOWER, TicketRows.lowerCase(authorName));
        String identifier = TextLimits.requireIfPresent(TICKET_REPLIES.CREATOR_IDENTIFIER, creatorIdentifier);
        List<String> replies = ColumnLookups.matchable(TICKET_REPLIES.ID, replyIds);
        unitOfWork.write(() -> {
            for (List<String> chunk : InClauseChunks.of(replies)) {
                dsl.update(TICKET_REPLIES)
                    .set(TICKET_REPLIES.AUTHOR_NAME, name)
                    .set(TICKET_REPLIES.AUTHOR_NAME_LOWER, nameLower)
                    .set(TICKET_REPLIES.CREATOR_IDENTIFIER, identifier)
                    .where(ColumnLookups.matches(TICKET_REPLIES.TICKET_ID, id))
                    .and(TICKET_REPLIES.ID.in(chunk))
                    .execute();
            }
        });
    }

    @Override
    public void addTags(String id, Collection<String> tags, Instant now) {
        List<String> added = sortedValues(TICKET_TAGS.TAG, tags);
        if (added.isEmpty()) {
            return;
        }
        String ticketId = TextLimits.require(TICKET_TAGS.TICKET_ID, id);
        unitOfWork.write(() -> {
            touch(ticketId, now);
            insertTags(ticketId, added);
        });
    }

    @Override
    public void removeTags(String id, Collection<String> tags, Instant now) {
        List<String> removed = ColumnLookups.matchable(TICKET_TAGS.TAG, tags);
        if (removed.isEmpty()) {
            return;
        }
        unitOfWork.write(() -> {
            touch(id, now);
            for (List<String> chunk : InClauseChunks.of(removed)) {
                dsl.deleteFrom(TICKET_TAGS)
                    .where(ColumnLookups.matches(TICKET_TAGS.TICKET_ID, id))
                    .and(TICKET_TAGS.TAG.in(chunk))
                    .execute();
            }
        });
    }

    @Override
    public void replaceAssignees(String id, Collection<String> assignees, Instant now) {
        String ticketId = TextLimits.require(TICKET_ASSIGNEES.TICKET_ID, id);
        List<String> replacement = sortedValues(TICKET_ASSIGNEES.ASSIGNEE, assignees);
        unitOfWork.write(() -> {
            touch(ticketId, now);
            dsl.deleteFrom(TICKET_ASSIGNEES).where(TICKET_ASSIGNEES.TICKET_ID.eq(ticketId)).execute();
            insertAssignees(ticketId, replacement);
        });
    }

    @Override
    public void updateHeader(String id, int expectedVersion, TicketHeaderPatch patch) {
        Map<Field<?>, Object> changes = rows.changes(patch);
        unitOfWork.write(() -> {
            int updated = dsl.update(TICKETS)
                .set(changes)
                .set(TICKETS.VERSION, TICKETS.VERSION.plus(1))
                .where(ColumnLookups.matches(TICKETS.ID, id))
                .and(TICKETS.VERSION.eq(expectedVersion))
                .execute();
            if (updated == 0) {
                throw new ConcurrentUpdateException("Ticket " + id + " changed after version " + expectedVersion + " was read");
            }
        });
    }

    @Override
    public int closeIfUnlocked(Collection<String> ids, Instant now) {
        List<String> keys = ticketKeys(ids);
        return unitOfWork.write(() -> InClauseChunks.<String>sum(keys, chunk -> dsl.update(TICKETS)
            .set(TICKETS.STATUS, TicketStatus.CLOSED.getId())
            .set(TICKETS.IS_LOCKED, true)
            .set(TICKETS.UPDATED_AT, now)
            .set(TICKETS.VERSION, TICKETS.VERSION.plus(1))
            .where(TICKETS.ID.in(chunk))
            .and(TICKETS.IS_LOCKED.eq(false))
            .execute()));
    }

    @Override
    public long clearReplayReferences(Collection<String> replayIds) {
        List<String> keys = replayKeys(replayIds);
        Supplier<Integer> clearing = () -> InClauseChunks.<String>sum(keys, chunk -> dsl.update(TICKETS)
            .setNull(TICKETS.REPLAY_URL)
            .setNull(TICKETS.REPLAY_ID)
            .set(TICKETS.VERSION, TICKETS.VERSION.plus(1))
            .where(TICKETS.REPLAY_ID.in(chunk))
            .execute());
        return unitOfWork.write(clearing);
    }

    @Override
    public Map<String, String> findReplayIdsByTicketId(Collection<String> replayIds) {
        return InClauseChunks.collect(replayKeys(replayIds), chunk -> dsl.select(TICKETS.ID, TICKETS.REPLAY_ID)
                .from(TICKETS)
                .where(TICKETS.REPLAY_ID.in(chunk))
                .fetch())
            .stream()
            .collect(toMap(row -> row.value1(), row -> row.value2()));
    }

    @Override
    public Set<String> findReplayIdsReferencedByUnresolvedTicket(Collection<String> replayIds) {
        return InClauseChunks.collect(replayKeys(replayIds), chunk -> dsl.selectDistinct(TICKETS.REPLAY_ID)
                .from(TICKETS)
                .where(TICKETS.REPLAY_ID.in(chunk))
                .and(TICKETS.STATUS.ne(TicketStatus.CLOSED.getId()))
                .fetch(TICKETS.REPLAY_ID))
            .stream()
            .collect(toSet());
    }

    @Override
    public Map<String, TicketReply> firstReplies(Collection<String> ticketIds) {
        Map<String, TicketReply> first = new LinkedHashMap<>();
        edgeReplies(ticketKeys(ticketIds), seq -> DSL.min(seq))
            .forEach(row -> first.put(row.getTicketId(), rows.reply(row)));
        return first;
    }

    @Override
    public Optional<ThreadSize> findThreadSize(String id) {
        Field<Integer> playerCharacters = DSL.coalesce(DSL.sum(DSL.coalesce(DSL.charLength(TICKET_REPLIES.CONTENT), 0)
            .plus(DSL.coalesce(DSL.charLength(TICKET_REPLIES.ATTACHMENTS_JSON), 0))), DSL.inline(0)).cast(Integer.class);
        return dsl.select(TICKETS.REPLY_COUNT, TICKETS.IS_LOCKED)
            .from(TICKETS)
            .where(ColumnLookups.matches(TICKETS.ID, id))
            .fetchOptional()
            .map(header -> new ThreadSize(header.value1(), header.value2(), dsl.select(playerCharacters)
                .from(TICKET_REPLIES)
                .where(TICKET_REPLIES.TICKET_ID.eq(id))
                .and(TICKET_REPLIES.IS_STAFF.eq(false))
                .fetchSingle()
                .value1()));
    }

    @Override
    public int storedCharacters(TicketReply reply) {
        return rows.storedCharacters(reply);
    }

    private <T> T readFiltered(TicketSearchFilter filter, Supplier<T> query) {
        return TicketConditions.hasFreeText(filter) ? unitOfWork.search(query) : unitOfWork.read(query);
    }

    private List<TicketsRecord> involving(String playerUuid, Condition filter, int limit) {
        Map<String, TicketsRecord> found = new LinkedHashMap<>();
        for (TableField<TicketsRecord, String> role : List.of(TICKETS.CREATOR_UUID, TICKETS.REPORTED_PLAYER_UUID)) {
            dsl.selectFrom(TICKETS)
                .where(isPlayer(role, playerUuid))
                .and(filter)
                .orderBy(TICKETS.CREATED_AT.desc(), TICKETS.ID.desc())
                .limit(limit)
                .fetch()
                .forEach(ticket -> found.put(ticket.getId(), ticket));
        }
        return found.values().stream().sorted(CREATED_FIRST.reversed()).limit(limit).toList();
    }

    private static Condition activeWithReplies() {
        return TicketConditions.notUnfinished().and(TICKETS.REPLY_COUNT.gt(0));
    }

    private static List<SortField<?>> order(TicketSortOption sort) {
        return switch (sort) {
            case NEWEST -> List.of(TICKETS.CREATED_AT.desc(), TICKETS.ID.desc());
            case OLDEST -> List.of(TICKETS.CREATED_AT.asc(), TICKETS.ID.asc());
            case RECENTLY_UPDATED -> List.of(TICKETS.UPDATED_AT.desc(), TICKETS.ID.desc());
            case LEAST_RECENTLY_UPDATED -> List.of(TICKETS.UPDATED_AT.asc(), TICKETS.ID.asc());
        };
    }

    private List<Ticket> threads(List<TicketsRecord> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        List<String> ids = found.stream().map(TicketsRecord::getId).toList();
        Map<String, List<String>> tags = tags(ids);
        Map<String, List<String>> assignees = assignees(ids);
        List<TicketRepliesRecord> replyRows = InClauseChunks.collect(ids, chunk -> dsl.selectFrom(TICKET_REPLIES)
            .where(TICKET_REPLIES.TICKET_ID.in(chunk))
            .orderBy(TICKET_REPLIES.TICKET_ID, TICKET_REPLIES.SEQ)
            .fetch());
        Map<String, List<TicketRepliesRecord>> replies = replyRows.stream().collect(groupingBy(TicketRepliesRecord::getTicketId));
        List<TicketNotesRecord> noteRows = InClauseChunks.collect(ids, chunk -> dsl.selectFrom(TICKET_NOTES)
            .where(TICKET_NOTES.TICKET_ID.in(chunk))
            .orderBy(TICKET_NOTES.TICKET_ID, TICKET_NOTES.SEQ)
            .fetch());
        Map<String, List<TicketNotesRecord>> notes = noteRows.stream().collect(groupingBy(TicketNotesRecord::getTicketId));
        return found.stream()
            .map(row -> rows.ticket(row,
                tags.getOrDefault(row.getId(), List.of()),
                assignees.getOrDefault(row.getId(), List.of()),
                replies.getOrDefault(row.getId(), List.of()),
                notes.getOrDefault(row.getId(), List.of())))
            .toList();
    }

    private List<Ticket> listed(List<TicketsRecord> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        List<String> ids = found.stream().map(TicketsRecord::getId).toList();
        Map<String, List<String>> tags = tags(ids);
        Map<String, List<String>> assignees = assignees(ids);
        return found.stream()
            .map(row -> rows.ticket(row,
                tags.getOrDefault(row.getId(), List.of()),
                assignees.getOrDefault(row.getId(), List.of()),
                List.of(),
                List.of()))
            .toList();
    }

    private List<ListedTicket> withDigests(List<TicketsRecord> found) {
        List<Ticket> tickets = listed(found);
        Map<String, ThreadDigest> digests = digests(tickets.stream().map(Ticket::getId).toList());
        return tickets.stream()
            .map(ticket -> new ListedTicket(ticket, digests.getOrDefault(ticket.getId(), ThreadDigest.EMPTY)))
            .toList();
    }

    private Map<String, ThreadDigest> digests(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Field<Integer> visible = DSL.count();
        Field<Integer> fromStaff = DSL.count().filterWhere(TICKET_REPLIES.IS_STAFF.isTrue());
        Map<String, TicketReply> first = firstReplies(ids);
        Map<String, ThreadDigest> digests = new LinkedHashMap<>();
        InClauseChunks.collect(ids, chunk -> dsl.select(TICKET_REPLIES.TICKET_ID, visible, fromStaff)
                .from(TICKET_REPLIES)
                .where(TICKET_REPLIES.TICKET_ID.in(chunk))
                .groupBy(TICKET_REPLIES.TICKET_ID)
                .fetch())
            .forEach(row -> digests.put(row.value1(),
                new ThreadDigest(row.value2(), row.value3() > 0, first.get(row.value1()))));
        return digests;
    }

    private List<TicketSummary> summaries(List<TicketsRecord> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        List<String> ids = found.stream().map(TicketsRecord::getId).toList();
        Map<String, List<String>> tags = tags(ids);
        Map<String, List<String>> assignees = assignees(ids);
        Map<String, TicketRepliesRecord> lastReplies = edgeReplies(ids, seq -> DSL.max(seq)).stream()
            .collect(toMap(TicketRepliesRecord::getTicketId, Function.identity()));
        return found.stream()
            .map(row -> rows.summary(row,
                tags.getOrDefault(row.getId(), List.of()),
                assignees.getOrDefault(row.getId(), List.of()),
                lastReplies.get(row.getId())))
            .toList();
    }

    private List<TicketRepliesRecord> edgeReplies(List<String> ticketIds, Function<Field<Integer>, Field<Integer>> edge) {
        TicketReplies thread = TICKET_REPLIES.as("thread");
        return InClauseChunks.collect(ticketIds, chunk -> dsl.selectFrom(TICKET_REPLIES)
            .where(TICKET_REPLIES.TICKET_ID.in(chunk))
            .and(TICKET_REPLIES.SEQ.eq(DSL.select(edge.apply(thread.SEQ))
                .from(thread)
                .where(thread.TICKET_ID.eq(TICKET_REPLIES.TICKET_ID))))
            .fetch());
    }

    private Map<String, List<String>> tags(List<String> ids) {
        List<TicketTagsRecord> found = InClauseChunks.collect(ids, chunk -> dsl.selectFrom(TICKET_TAGS)
            .where(TICKET_TAGS.TICKET_ID.in(chunk))
            .orderBy(TICKET_TAGS.TICKET_ID, TICKET_TAGS.TAG)
            .fetch());
        return found.stream()
            .collect(groupingBy(TicketTagsRecord::getTicketId, mapping(TicketTagsRecord::getTag, toList())));
    }

    private Map<String, List<String>> assignees(List<String> ids) {
        List<TicketAssigneesRecord> found = InClauseChunks.collect(ids, chunk -> dsl.selectFrom(TICKET_ASSIGNEES)
            .where(TICKET_ASSIGNEES.TICKET_ID.in(chunk))
            .orderBy(TICKET_ASSIGNEES.TICKET_ID, TICKET_ASSIGNEES.ASSIGNEE)
            .fetch());
        return found.stream()
            .collect(groupingBy(TicketAssigneesRecord::getTicketId, mapping(TicketAssigneesRecord::getAssignee, toList())));
    }

    private void insertTags(String ticketId, List<String> tags) {
        for (String tag : tags) {
            TicketTagsRecord row = new TicketTagsRecord();
            row.setTicketId(ticketId);
            row.setTag(tag);
            insertIfAbsent.onPrimaryKey(TICKET_TAGS).insert(row);
        }
    }

    private void insertAssignees(String ticketId, List<String> assignees) {
        for (String assignee : assignees) {
            TicketAssigneesRecord row = new TicketAssigneesRecord();
            row.setTicketId(ticketId);
            row.setAssignee(assignee);
            insertIfAbsent.onPrimaryKey(TICKET_ASSIGNEES).insert(row);
        }
    }

    private void touch(String ticketId, Instant now) {
        dsl.update(TICKETS).set(TICKETS.UPDATED_AT, now).where(ColumnLookups.matches(TICKETS.ID, ticketId)).execute();
    }

    private static Condition isPlayer(TableField<?, String> field, String uuid) {
        return ColumnLookups.matches(field, UuidUtils.normalize(uuid));
    }

    private static List<String> ticketKeys(Collection<String> ids) {
        return ColumnLookups.matchable(TICKETS.ID, ids);
    }

    private static List<String> replayKeys(Collection<String> replayIds) {
        return ColumnLookups.matchable(
            TICKETS.REPLAY_ID, replayIds.stream().filter(id -> id != null && !id.isBlank()).toList());
    }

    private static List<String> sortedValues(TableField<?, String> field, @Nullable Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        Stream<String> bound = values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> TextLimits.require(field, value));
        return List.copyOf(bound.collect(toCollection(TreeSet::new)));
    }
}
