package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_TICKETS;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.Punishments;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentEvidenceRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentModificationsRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentNotesRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentTicketsRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectOnConditionStep;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.UpdateSetMoreStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class JooqPunishmentRepository implements PunishmentRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;
    private final ChildRows childRows;
    private final PunishmentRows rows;
    private final PunishmentChildren children;

    public JooqPunishmentRepository(DSLContext dsl, UnitOfWork unitOfWork, InsertIfAbsent insertIfAbsent,
                                    ChildRows childRows, JsonColumns jsonColumns) {
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
        this.insertIfAbsent = insertIfAbsent;
        this.childRows = childRows;
        this.rows = new PunishmentRows(jsonColumns);
        this.children = new PunishmentChildren(dsl, rows);
    }

    @Override
    public boolean insertIfAbsent(String playerId, Punishment punishment, ResolvedCategory category) {
        return unitOfWork.write(() -> {
            PunishmentsRecord header = rows.header(playerId, punishment, category);
            if (!insertIfAbsent.onPrimaryKey(PUNISHMENTS).insert(header)) {
                return false;
            }
            insertChildren(header.getId(), punishment);
            return true;
        });
    }

    @Override
    public Optional<PunishmentRecord> findById(String id) {
        return unitOfWork.read(() -> completeRecords(selectWithPlayer()
            .where(idIs(id))
            .fetch())
            .stream()
            .findFirst());
    }

    @Override
    public Optional<PunishmentRecord> lockById(String id) {
        return unitOfWork.write(() -> {
            lockAll(List.of(id));
            return findById(id);
        });
    }

    @Override
    public void lockAll(Collection<String> idsInLockOrder) {
        unitOfWork.write(() -> {
            for (String id : idsInLockOrder) {
                if (TextLimits.canMatch(PUNISHMENTS.ID, id)) {
                    childRows.lockRow(PUNISHMENTS, PUNISHMENTS.ID, id);
                }
            }
        });
    }

    @Override
    public List<Punishment> findByPlayer(String playerId) {
        return unitOfWork.read(() -> children.complete(dsl.selectFrom(PUNISHMENTS)
            .where(ColumnLookups.matches(PUNISHMENTS.PLAYER_ID, playerId))
            .orderBy(PUNISHMENTS.ISSUED_AT, PUNISHMENTS.ID)
            .fetch()));
    }

    @Override
    public List<Punishment> findEnforcementRelevant(String playerId, Instant now) {
        return unitOfWork.read(() -> children.forEnforcement(dsl.selectFrom(PUNISHMENTS)
            .where(ColumnLookups.matches(PUNISHMENTS.PLAYER_ID, playerId))
            .and(PunishmentConditions.enforcementRelevantAt(now))
            .orderBy(PUNISHMENTS.ISSUED_AT, PUNISHMENTS.ID)
            .fetch()));
    }

    @Override
    public Map<String, List<Punishment>> findSyncRelevant(Collection<String> playerIds, Instant lastSync, Instant now) {
        List<String> storedPlayerIds = ColumnLookups.matchable(PUNISHMENTS.PLAYER_ID, playerIds);
        if (storedPlayerIds.isEmpty()) {
            return Map.of();
        }
        return unitOfWork.read(() -> {
            List<PunishmentsRecord> headers = InClauseChunks.collect(storedPlayerIds, chunk -> dsl.selectFrom(PUNISHMENTS)
                .where(PUNISHMENTS.PLAYER_ID.in(chunk))
                .and(PunishmentConditions.syncRelevant(lastSync, now))
                .orderBy(PUNISHMENTS.PLAYER_ID, PUNISHMENTS.ISSUED_AT, PUNISHMENTS.ID)
                .fetch());
            List<Punishment> punishments = children.forEnforcement(headers);
            Map<String, List<Punishment>> byPlayer = new LinkedHashMap<>();
            for (int index = 0; index < headers.size(); index++) {
                byPlayer.computeIfAbsent(headers.get(index).getPlayerId(), key -> new ArrayList<>()).add(punishments.get(index));
            }
            return byPlayer;
        });
    }

    @Override
    public List<PunishmentRecord> findActiveAltBlockingBansAmongLinked(String playerId, int sharedAccountLimit, Instant now) {
        return unitOfWork.read(() -> records(selectWithPlayer()
            .join(PLAYER_LINKS).on(PLAYER_LINKS.LINKED_PLAYER_ID.eq(PUNISHMENTS.PLAYER_ID))
            .where(ColumnLookups.matches(PLAYER_LINKS.PLAYER_ID, playerId))
            .and(PLAYER_LINKS.SHARED_CLEAN_ADDRESS.isTrue())
            .and(CleanLinkEvidence.currentlyShared(playerId, PUNISHMENTS.PLAYER_ID, sharedAccountLimit))
            .and(PUNISHMENTS.ALT_BLOCKING.eq(true))
            .and(PUNISHMENTS.ENFORCEMENT_CATEGORY.eq(ResolvedCategory.BAN.name()))
            .and(PunishmentConditions.activeAt(now))
            .orderBy(PUNISHMENTS.ISSUED_AT, PUNISHMENTS.ID)
            .fetch(), children::forEnforcement));
    }

    @Override
    public List<Punishment> findLinkedBansOfPlayer(String playerId, Collection<String> parentIds) {
        List<String> parents = ColumnLookups.matchable(PUNISHMENTS.LINKED_BAN_ID, parentIds);
        return unitOfWork.read(() -> children.forEnforcement(InClauseChunks.collect(parents, chunk -> dsl.selectFrom(PUNISHMENTS)
            .where(PUNISHMENTS.LINKED_BAN_ID.in(chunk))
            .and(ColumnLookups.matches(PUNISHMENTS.PLAYER_ID, playerId))
            .orderBy(PUNISHMENTS.ISSUED_AT, PUNISHMENTS.ID)
            .fetch())));
    }

    @Override
    public List<String> findLinkedBanIds(Collection<String> parentIds) {
        return InClauseChunks.collect(ColumnLookups.matchable(PUNISHMENTS.LINKED_BAN_ID, parentIds),
            chunk -> dsl.select(PUNISHMENTS.ID)
                .from(PUNISHMENTS)
                .where(PUNISHMENTS.LINKED_BAN_ID.in(chunk))
                .fetch(PUNISHMENTS.ID));
    }

    @Override
    public boolean hasQueuedWhileOnline(String playerId) {
        return dsl.fetchExists(dsl.selectOne()
            .from(PUNISHMENTS)
            .join(PLAYERS).on(PLAYERS.ID.eq(PUNISHMENTS.PLAYER_ID))
            .where(ColumnLookups.matches(PUNISHMENTS.PLAYER_ID, playerId))
            .and(PLAYERS.IS_ONLINE.eq(true))
            .and(PunishmentConditions.queued(PUNISHMENTS)));
    }

    @Override
    public List<String> findOnlinePlayersQueuedBehindExpired(Instant expiredFrom, Instant expiredBefore) {
        Punishments queued = PUNISHMENTS.as("queued");
        return dsl.selectDistinct(PUNISHMENTS.PLAYER_ID)
            .from(PUNISHMENTS)
            .join(PLAYERS).on(PLAYERS.ID.eq(PUNISHMENTS.PLAYER_ID))
            .where(PUNISHMENTS.ACTIVE_UNTIL_AT.ge(expiredFrom))
            .and(PUNISHMENTS.ACTIVE_UNTIL_AT.lt(expiredBefore))
            .and(PUNISHMENTS.ENFORCEMENT_CATEGORY.in(ResolvedCategory.BAN.name(), ResolvedCategory.MUTE.name()))
            .and(PLAYERS.IS_ONLINE.eq(true))
            .andExists(dsl.selectOne()
                .from(queued)
                .where(queued.PLAYER_ID.eq(PUNISHMENTS.PLAYER_ID))
                .and(queued.ENFORCEMENT_CATEGORY.eq(PUNISHMENTS.ENFORCEMENT_CATEGORY))
                .and(PunishmentConditions.queued(queued)))
            .orderBy(PUNISHMENTS.PLAYER_ID)
            .fetch(PUNISHMENTS.PLAYER_ID);
    }

    @Override
    public void appendModification(String id, int expectedVersion, PunishmentModification modification, PunishmentPatch patch,
                                   List<PunishmentNote> notes) {
        unitOfWork.write(() -> {
            updateHeader(id, expectedVersion, patch);
            appendChild(PUNISHMENT_MODIFICATIONS, PUNISHMENT_MODIFICATIONS.PUNISHMENT_ID, PUNISHMENT_MODIFICATIONS.SEQ,
                rows.modification(id, modification));
            appendNotes(id, notes);
            deriveState(id);
        });
    }

    @Override
    public boolean markStarted(String id, Instant startedAt) {
        return unitOfWork.write(() -> {
            Optional<PunishmentsRecord> found = findHeader(id);
            if (found.isEmpty() || found.get().getStartedAt() != null) {
                return false;
            }
            PunishmentsRecord current = found.get();
            Punishment started = children.forDerivation(current);
            started.setStarted(startedAt);
            started.data().removeStatus();
            int updated = bumpVersion()
                .set(PUNISHMENTS.STARTED_AT, startedAt)
                .setNull(PUNISHMENTS.STATUS)
                .set(PUNISHMENTS.ACTIVE_UNTIL_AT, PunishmentState.activeUntil(started))
                .where(idIs(id))
                .and(PUNISHMENTS.STARTED_AT.isNull())
                .and(PUNISHMENTS.VERSION.eq(current.getVersion()))
                .execute();
            requireUpdated(id, updated);
            return true;
        });
    }

    @Override
    public boolean promote(String id) {
        return unitOfWork.write(() -> {
            Optional<PunishmentsRecord> found = findHeader(id);
            if (found.isEmpty() || !PunishmentStatus.UNSTARTED.equals(found.get().getStatus())) {
                return false;
            }
            PunishmentsRecord current = found.get();
            Punishment promoted = children.forDerivation(current);
            if (promoted.hasEndedEnforcement()) {
                return false;
            }
            promoted.data().removeStatus();
            int updated = bumpVersion()
                .setNull(PUNISHMENTS.STATUS)
                .set(PUNISHMENTS.ACTIVE_UNTIL_AT, PunishmentState.activeUntil(promoted))
                .where(idIs(id))
                .and(PUNISHMENTS.VERSION.eq(current.getVersion()))
                .execute();
            requireUpdated(id, updated);
            return true;
        });
    }

    @Override
    public void patch(String id, int expectedVersion, PunishmentPatch patch) {
        unitOfWork.write(() -> {
            updateHeader(id, expectedVersion, patch);
            deriveState(id);
        });
    }

    @Override
    public void appendNote(String id, PunishmentNote note) {
        unitOfWork.write(() -> {
            lockForAppend(id);
            appendNotes(id, List.of(note));
        });
    }

    @Override
    public void appendEvidence(String id, List<PunishmentEvidence> evidence, @Nullable PunishmentNote noteOrNull) {
        unitOfWork.write(() -> {
            lockForAppend(id);
            for (PunishmentEvidence item : evidence) {
                appendChild(PUNISHMENT_EVIDENCE, PUNISHMENT_EVIDENCE.PUNISHMENT_ID, PUNISHMENT_EVIDENCE.SEQ,
                    rows.evidence(id, item));
            }
            if (noteOrNull != null) {
                appendNotes(id, List.of(noteOrNull));
            }
        });
    }

    @Override
    public void attachTicket(String id, String ticketId, PunishmentNote note) {
        unitOfWork.write(() -> {
            lockForAppend(id);
            for (PunishmentTicketsRecord row : rows.tickets(id, List.of(ticketId))) {
                insertIfAbsent.onPrimaryKey(PUNISHMENT_TICKETS).insert(row);
            }
            appendNotes(id, List.of(note));
        });
    }

    @Override
    public void replaceTickets(String id, List<String> ticketIds) {
        unitOfWork.write(() -> {
            lockForAppend(id);
            dsl.deleteFrom(PUNISHMENT_TICKETS)
                .where(ColumnLookups.matches(PUNISHMENT_TICKETS.PUNISHMENT_ID, id))
                .execute();
            rows.tickets(id, ticketIds).forEach(this::insertTicket);
        });
    }

    @Override
    public Set<String> findTicketIdsHeldByActiveOrUnstarted(Collection<String> ticketIds, Instant now) {
        List<String> held = InClauseChunks.collect(
            ColumnLookups.matchable(PUNISHMENT_TICKETS.TICKET_ID, ticketIds),
            chunk -> dsl.selectDistinct(PUNISHMENT_TICKETS.TICKET_ID)
                .from(PUNISHMENT_TICKETS)
                .join(PUNISHMENTS).on(PUNISHMENTS.ID.eq(PUNISHMENT_TICKETS.PUNISHMENT_ID))
                .where(PUNISHMENT_TICKETS.TICKET_ID.in(chunk))
                .and(PunishmentConditions.activeOrUnstartedAt(now))
                .fetch(PUNISHMENT_TICKETS.TICKET_ID));
        return Set.copyOf(held);
    }

    @Override
    public List<PunishmentRecord> findRecent(Instant issuedAfter, int limit) {
        return findNewest(PUNISHMENTS.ISSUED_AT.ge(issuedAfter), limit);
    }

    @Override
    public List<PunishmentRecord> findRecentByIssuer(IssuerMatch issuer, Instant issuedAfter, int limit) {
        return findNewest(PUNISHMENTS.ISSUED_AT.ge(issuedAfter).and(PunishmentConditions.issuedBy(issuer)), limit);
    }

    @Override
    public List<PunishmentRecord> findLinkedBans(String parentId) {
        return unitOfWork.read(() -> completeRecords(selectWithPlayer()
            .where(ColumnLookups.matches(PUNISHMENTS.LINKED_BAN_ID, parentId))
            .orderBy(PUNISHMENTS.ISSUED_AT, PUNISHMENTS.ID)
            .fetch()));
    }

    @Override
    public List<String> pageIdsByIssuer(IssuerMatch issuer, @Nullable Instant issuedFrom, @Nullable Instant issuedUntil,
                                        @Nullable String afterId, int limit) {
        Condition issued = PunishmentConditions.issuedBy(issuer);
        if (issuedFrom != null) {
            issued = issued.and(PUNISHMENTS.ISSUED_AT.ge(issuedFrom));
        }
        if (issuedUntil != null) {
            issued = issued.and(PUNISHMENTS.ISSUED_AT.le(issuedUntil));
        }
        return pageIds(issued, afterId, limit);
    }

    @Override
    public List<String> pageActiveIdsByTypes(Collection<Integer> typeOrdinals, Instant now, @Nullable String afterId, int limit) {
        return typeOrdinals.isEmpty()
            ? List.of()
            : pageIds(PunishmentConditions.activeAt(now).and(PUNISHMENTS.TYPE_ORDINAL.in(typeOrdinals)), afterId, limit);
    }

    @Override
    public Optional<PunishmentSearchRow> findSearchRow(String id) {
        return unitOfWork.read(() -> selectSearchRows().where(idIs(id)).fetchOptional(JooqPunishmentRepository::toSearchRow));
    }

    @Override
    public List<PunishmentSearchRow> findSearchRowsOfPlayer(String playerId) {
        return unitOfWork.read(() -> selectSearchRows()
            .where(ColumnLookups.matches(PUNISHMENTS.PLAYER_ID, playerId))
            .orderBy(PUNISHMENTS.ISSUED_AT.desc(), PUNISHMENTS.ID.desc())
            .fetch(JooqPunishmentRepository::toSearchRow));
    }

    @Override
    public List<PunishmentSearchRow> findRecentSearchRows(int limit) {
        return unitOfWork.report(() -> selectSearchRows()
            .orderBy(PUNISHMENTS.ISSUED_AT.desc(), PUNISHMENTS.ID.desc())
            .limit(limit)
            .fetch(JooqPunishmentRepository::toSearchRow));
    }

    @Override
    public Map<ResolvedCategory, Long> countActiveByCategory(Instant now) {
        Map<ResolvedCategory, Long> counts = new EnumMap<>(ResolvedCategory.class);
        for (ResolvedCategory category : ResolvedCategory.values()) {
            counts.put(category, 0L);
        }
        dsl.select(PUNISHMENTS.ENFORCEMENT_CATEGORY, DSL.count())
            .from(PUNISHMENTS)
            .where(PunishmentConditions.activeAt(now))
            .groupBy(PUNISHMENTS.ENFORCEMENT_CATEGORY)
            .fetch()
            .forEach(row -> counts.put(ResolvedCategory.fromStored(row.value1()), row.value2().longValue()));
        return counts;
    }

    @Override
    public List<IssuerCount> countByIssuer(Instant issuedFrom) {
        Field<Integer> count = DSL.count();
        return unitOfWork.report(() -> dsl.select(PUNISHMENTS.ISSUER_KIND, PUNISHMENTS.ISSUER_ID, PUNISHMENTS.ISSUER_NAME, count)
            .from(PUNISHMENTS)
            .where(PUNISHMENTS.ISSUED_AT.ge(issuedFrom))
            .groupBy(PUNISHMENTS.ISSUER_KIND, PUNISHMENTS.ISSUER_ID, PUNISHMENTS.ISSUER_NAME)
            .orderBy(count.desc(), PUNISHMENTS.ISSUER_KIND.asc(), PUNISHMENTS.ISSUER_ID.asc().nullsFirst(),
                PUNISHMENTS.ISSUER_NAME.asc().nullsFirst())
            .fetch(row -> new IssuerCount(PunishmentIssuerKind.fromStored(row.value1()), row.value2(), row.value3(), row.value4())));
    }

    @Override
    public long countActive(Instant now) {
        return dsl.selectCount()
            .from(PUNISHMENTS)
            .where(PunishmentConditions.activeAt(now))
            .fetchSingle()
            .value1()
            .longValue();
    }

    @Override
    public Map<Integer, Long> countActiveByType(Instant now) {
        Field<Integer> count = DSL.count();
        return unitOfWork.report(() -> {
            Map<Integer, Long> counts = new LinkedHashMap<>();
            dsl.select(PUNISHMENTS.TYPE_ORDINAL, count)
                .from(PUNISHMENTS)
                .where(PunishmentConditions.activeAt(now))
                .groupBy(PUNISHMENTS.TYPE_ORDINAL)
                .orderBy(PUNISHMENTS.TYPE_ORDINAL)
                .fetch()
                .forEach(row -> counts.put(row.value1(), row.value2().longValue()));
            return counts;
        });
    }

    @Override
    public PunishmentListPage pageListed(PunishmentListQuery query, Instant now, @Nullable PunishmentListCursor after, int limit) {
        Field<Long> key = PunishmentListOrder.keyOf(query.sortKey());
        Field<Long> selectedKey = key.as(PunishmentListOrder.KEY);
        return unitOfWork.report(() -> {
            List<Record> rows = dsl.select(PUNISHMENTS.fields())
                .select(selectedKey)
                .from(PUNISHMENTS)
                .where(PunishmentConditions.listed(query, now))
                .and(PunishmentListOrder.after(key, query.ascending(), after))
                .orderBy(PunishmentListOrder.orderBy(key, query.ascending()))
                .limit(limit + 1)
                .fetch();
            boolean hasMore = rows.size() > limit;
            List<Record> shown = hasMore ? rows.subList(0, limit) : rows;
            PunishmentListCursor next = hasMore
                ? new PunishmentListCursor(shown.getLast().get(selectedKey), shown.getLast().get(PUNISHMENTS.ID))
                : null;
            return new PunishmentListPage(recordsOfHeaders(shown.stream().map(row -> row.into(PUNISHMENTS)).toList()), next);
        });
    }

    private List<PunishmentRecord> recordsOfHeaders(List<PunishmentsRecord> headers) {
        List<Punishment> punishments = children.complete(headers);
        Map<String, PlayersRecord> players = InClauseChunks.<String, PlayersRecord>collect(
                headers.stream().map(PunishmentsRecord::getPlayerId).toList(),
                chunk -> dsl.selectFrom(PLAYERS).where(PLAYERS.ID.in(chunk)).fetch())
            .stream()
            .collect(Collectors.toMap(PlayersRecord::getId, Function.identity()));
        List<PunishmentRecord> records = new ArrayList<>(headers.size());
        for (int index = 0; index < headers.size(); index++) {
            PunishmentsRecord header = headers.get(index);
            PlayersRecord player = owningPlayer(players, header);
            records.add(new PunishmentRecord(header.getPlayerId(), player.getMinecraftUuid(), player.getUsername(),
                punishments.get(index), header.getVersion()));
        }
        return records;
    }

    private List<PunishmentRecord> findNewest(Condition filter, int limit) {
        return unitOfWork.read(() -> completeRecords(selectWithPlayer()
            .where(filter)
            .orderBy(PUNISHMENTS.ISSUED_AT.desc(), PUNISHMENTS.ID.desc())
            .limit(limit)
            .fetch()));
    }

    private List<String> pageIds(Condition filter, @Nullable String afterId, int limit) {
        Condition afterCursor = afterId == null ? DSL.noCondition() : PUNISHMENTS.ID.gt(afterId);
        return dsl.select(PUNISHMENTS.ID)
            .from(PUNISHMENTS)
            .where(filter)
            .and(afterCursor)
            .orderBy(PUNISHMENTS.ID)
            .limit(limit)
            .fetch(PUNISHMENTS.ID);
    }

    private SelectOnConditionStep<Record> selectSearchRows() {
        return dsl.select(List.<Field<?>>of(PUNISHMENTS.ID, PLAYERS.USERNAME, PUNISHMENTS.TYPE_ORDINAL, PUNISHMENTS.ISSUER_NAME,
                PUNISHMENTS.ISSUER_ID, PUNISHMENTS.REASON, PUNISHMENTS.ISSUED_AT, PUNISHMENTS.ACTIVE_UNTIL_AT))
            .from(PUNISHMENTS)
            .join(PLAYERS).on(PLAYERS.ID.eq(PUNISHMENTS.PLAYER_ID));
    }

    private static PlayersRecord owningPlayer(Map<String, PlayersRecord> players, PunishmentsRecord header) {
        PlayersRecord player = players.get(header.getPlayerId());
        if (player == null) {
            throw new IllegalStateException("Punishment " + header.getId() + " references missing player " + header.getPlayerId());
        }
        return player;
    }

    private static PunishmentSearchRow toSearchRow(Record row) {
        return new PunishmentSearchRow(
            row.get(PUNISHMENTS.ID),
            row.get(PLAYERS.USERNAME),
            row.get(PUNISHMENTS.TYPE_ORDINAL),
            row.get(PUNISHMENTS.ISSUER_NAME),
            row.get(PUNISHMENTS.ISSUER_ID),
            row.get(PUNISHMENTS.REASON),
            row.get(PUNISHMENTS.ISSUED_AT),
            row.get(PUNISHMENTS.ACTIVE_UNTIL_AT));
    }

    private SelectOnConditionStep<Record> selectWithPlayer() {
        return dsl.select(PUNISHMENTS.fields())
            .select(PLAYERS.MINECRAFT_UUID, PLAYERS.USERNAME)
            .from(PUNISHMENTS)
            .join(PLAYERS).on(PLAYERS.ID.eq(PUNISHMENTS.PLAYER_ID));
    }

    private List<PunishmentRecord> completeRecords(List<Record> joined) {
        return records(joined, children::complete);
    }

    private List<PunishmentRecord> records(List<Record> joined, Function<List<PunishmentsRecord>, List<Punishment>> assembler) {
        List<PunishmentsRecord> headers = joined.stream().map(row -> row.into(PUNISHMENTS)).toList();
        List<Punishment> punishments = assembler.apply(headers);
        List<PunishmentRecord> records = new ArrayList<>(headers.size());
        for (int index = 0; index < headers.size(); index++) {
            Record row = joined.get(index);
            records.add(new PunishmentRecord(headers.get(index).getPlayerId(), row.get(PLAYERS.MINECRAFT_UUID),
                row.get(PLAYERS.USERNAME), punishments.get(index), headers.get(index).getVersion()));
        }
        return records;
    }

    private Optional<PunishmentsRecord> findHeader(String id) {
        return dsl.selectFrom(PUNISHMENTS).where(idIs(id)).fetchOptional();
    }

    private void updateHeader(String id, int expectedVersion, PunishmentPatch patch) {
        UpdateSetMoreStep<PunishmentsRecord> update = bumpVersion();
        update = setIfPresent(update, PUNISHMENTS.STATUS, TextLimits.requireIfPresent(PUNISHMENTS.STATUS, patch.status()));
        update = setIfPresent(update, PUNISHMENTS.DURATION_MS, patch.duration());
        update = setIfPresent(update, PUNISHMENTS.ALT_BLOCKING, patch.altBlocking());
        update = setIfPresent(update, PUNISHMENTS.WIPE_AFTER_EXPIRY, patch.wipeAfterExpiry());
        update = setIfPresent(update, PUNISHMENTS.STAT_WIPE_COMPLETED_AT, patch.statWipeCompletedAt());
        update = setIfPresent(update, PUNISHMENTS.APPEAL_OUTCOME,
            TextLimits.requireIfPresent(PUNISHMENTS.APPEAL_OUTCOME, patch.appealOutcome()));
        update = setIfPresent(update, PUNISHMENTS.APPEAL_TICKET_ID,
            TextLimits.requireIfPresent(PUNISHMENTS.APPEAL_TICKET_ID, patch.appealTicketId()));
        requireUpdated(id, update.where(idIs(id)).and(PUNISHMENTS.VERSION.eq(expectedVersion)).execute());
    }

    private static <T> UpdateSetMoreStep<PunishmentsRecord> setIfPresent(UpdateSetMoreStep<PunishmentsRecord> update,
                                                                       TableField<PunishmentsRecord, T> field,
                                                                       @Nullable T value) {
        return value == null ? update : update.set(field, value);
    }

    private UpdateSetMoreStep<PunishmentsRecord> bumpVersion() {
        return dsl.update(PUNISHMENTS).set(PUNISHMENTS.VERSION, PUNISHMENTS.VERSION.plus(1));
    }

    private static void requireUpdated(String id, int updatedRows) {
        if (updatedRows != 1) {
            throw new ConcurrentUpdateException("Punishment " + id + " changed while it was being updated");
        }
    }

    private void lockForAppend(String id) {
        bumpVersion().where(idIs(id)).execute();
    }

    private void deriveState(String id) {
        findHeader(id).ifPresent(header -> {
            Punishment current = children.forDerivation(header);
            UpdateSetMoreStep<PunishmentsRecord> update = dsl.update(PUNISHMENTS)
                .set(PUNISHMENTS.ACTIVE_UNTIL_AT, PunishmentState.activeUntil(current))
                .set(PUNISHMENTS.MODIFIED_AT, PunishmentRows.modifiedAt(current.getModifications()));
            if (current.hasUnstartedStatus() && current.hasEndedEnforcement()) {
                update = update.setNull(PUNISHMENTS.STATUS);
            }
            update.where(PUNISHMENTS.ID.eq(header.getId())).execute();
        });
    }

    private void appendNotes(String id, List<PunishmentNote> notes) {
        for (PunishmentNote note : notes) {
            appendChild(PUNISHMENT_NOTES, PUNISHMENT_NOTES.PUNISHMENT_ID, PUNISHMENT_NOTES.SEQ, rows.note(id, note));
        }
    }

    private <R extends Record> void appendChild(Table<R> table, TableField<R, String> parent, TableField<R, Integer> seq, R row) {
        childRows.append(JooqPunishmentRepository::parentAlreadyLocked, table, parent, seq, row);
    }

    private static void parentAlreadyLocked() {
    }

    private void insertChildren(String id, Punishment punishment) {
        int seq = 1;
        for (PunishmentModification modification : punishment.getModifications()) {
            PunishmentModificationsRecord row = rows.modification(id, modification);
            row.setSeq(seq++);
            dsl.insertInto(PUNISHMENT_MODIFICATIONS).set(row).execute();
        }
        seq = 1;
        for (PunishmentNote note : punishment.getNotes()) {
            PunishmentNotesRecord row = rows.note(id, note);
            row.setSeq(seq++);
            dsl.insertInto(PUNISHMENT_NOTES).set(row).execute();
        }
        seq = 1;
        for (PunishmentEvidence evidence : punishment.getEvidence()) {
            PunishmentEvidenceRecord row = rows.evidence(id, evidence);
            row.setSeq(seq++);
            dsl.insertInto(PUNISHMENT_EVIDENCE).set(row).execute();
        }
        rows.tickets(id, punishment.getAttachedTicketIds()).forEach(this::insertTicket);
    }

    private void insertTicket(PunishmentTicketsRecord row) {
        dsl.insertInto(PUNISHMENT_TICKETS).set(row).execute();
    }

    private static Condition idIs(String id) {
        return ColumnLookups.matches(PUNISHMENTS.ID, id);
    }
}
