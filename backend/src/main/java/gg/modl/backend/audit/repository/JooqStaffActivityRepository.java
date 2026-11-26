package gg.modl.backend.audit.repository;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;

import gg.modl.backend.analytics.repository.GroupedCounts;
import gg.modl.backend.analytics.repository.TimeBucketCount;
import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.DerivedColumns;
import gg.modl.backend.database.FreeText;
import gg.modl.backend.database.Sequencer;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.player.repository.IssuerMatch;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqStaffActivityRepository implements StaffActivityRepository {
    static final int DETAIL_LIMIT = 50;

    private static final String SYSTEM_SOURCE = "system";
    private static final String MODERATION_LEVEL = "moderation";
    private static final List<String> MODERATION_WORDS = List.of("ban", "mute", "kick", "punishment");
    private static final String TICKET_WORD = "ticket";
    private static final long MINUTE_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final Comparator<PunishmentRecord> NEWEST_FIRST = Comparator
        .comparing((PunishmentRecord found) -> found.punishment().getIssued())
        .thenComparing(found -> found.punishment().getId())
        .reversed();

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final PunishmentRepository punishmentRepository;

    @Override
    public List<StaffActionSummary> summarizeLogActivityBySource(Instant startDate) {
        Field<Integer> total = DSL.count();
        Field<Integer> ticketActions =
            DSL.count(DSL.when(FreeText.contains(AUDIT_LOGS.DESCRIPTION, TICKET_WORD), DSL.inline(1)));
        Field<Integer> moderationActions = DSL.count(DSL.when(moderationAction(), DSL.inline(1)));
        return unitOfWork.report(() -> dsl
            .select(AUDIT_LOGS.SOURCE, total, ticketActions, moderationActions, DSL.max(AUDIT_LOGS.CREATED_AT))
            .from(AUDIT_LOGS)
            .where(AUDIT_LOGS.ID.ge(Sequencer.floorAt(startDate)))
            .and(AUDIT_LOGS.CREATED_AT.ge(startDate))
            .and(AUDIT_LOGS.SOURCE.ne(SYSTEM_SOURCE))
            .groupBy(AUDIT_LOGS.SOURCE)
            .orderBy(total.desc(), AUDIT_LOGS.SOURCE)
            .fetch(row -> new StaffActionSummary(row.value1(), row.value2(), row.value3(), row.value4(), row.value5())));
    }

    @Override
    public Map<String, Long> countTicketResponsesByStaff(Instant startDate) {
        return unitOfWork.report(() -> GroupedCounts.byKey(dsl, TICKET_REPLIES, TICKET_REPLIES.AUTHOR_NAME_LOWER,
            staffRepliesSince(startDate).and(TICKET_REPLIES.AUTHOR_NAME_LOWER.isNotNull())));
    }

    @Override
    public Map<String, Integer> averageResponseMinutesByStaff(Instant startDate) {
        return unitOfWork.report(() -> averageResponseMinutes(staffRepliesSince(startDate)));
    }

    @Override
    public int averageResponseMinutesOf(String username, Instant startDate) {
        return unitOfWork.report(() -> averageResponseMinutes(repliedBy(username, startDate)))
            .getOrDefault(username.toLowerCase(Locale.ROOT), 0);
    }

    private Map<String, Integer> averageResponseMinutes(Condition replies) {
        Field<String> author = TICKET_REPLIES.AUTHOR_NAME_LOWER.as("staff_name");
        Field<Long> waited = DSL.min(TICKET_REPLIES.CREATED_AT).coerce(Long.class)
            .minus(TICKETS.CREATED_AT.coerce(Long.class))
            .as("waited_millis");
        Table<?> firstResponses = DSL.select(author, waited)
            .from(TICKET_REPLIES)
            .join(TICKETS).on(TICKETS.ID.eq(TICKET_REPLIES.TICKET_ID))
            .where(replies)
            .and(TICKET_REPLIES.AUTHOR_NAME_LOWER.isNotNull())
            .groupBy(TICKET_REPLIES.AUTHOR_NAME_LOWER, TICKET_REPLIES.TICKET_ID, TICKETS.CREATED_AT)
            .asTable("first_responses");
        Field<String> staffName = DerivedColumns.of(firstResponses, author);
        Field<Long> waitedMillis = DerivedColumns.of(firstResponses, waited);
        Field<BigDecimal> wholeMinutesWaited = DSL.sum(waitedMillis.minus(waitedMillis.mod(DSL.inline(MINUTE_MILLIS))));
        Map<String, Integer> averages = new LinkedHashMap<>();
        dsl.select(staffName, wholeMinutesWaited, DSL.count())
            .from(firstResponses)
            .groupBy(staffName)
            .orderBy(staffName)
            .fetch()
            .forEach(row -> averages.put(row.value1(),
                Math.toIntExact(row.value2().longValue() / (MINUTE_MILLIS * row.value3()))));
        return averages;
    }

    @Override
    public List<PunishmentRecord> findPunishmentDetails(Collection<String> usernames, @Nullable String staffId, Instant startDate) {
        return unitOfWork.report(() -> usernames.stream()
            .flatMap(name -> punishmentRepository
                .findRecentByIssuer(new IssuerMatch(name, staffId), startDate, DETAIL_LIMIT)
                .stream())
            .collect(Collectors.toMap(found -> found.punishment().getId(), found -> found, (first, repeated) -> first))
            .values().stream()
            .sorted(NEWEST_FIRST)
            .limit(DETAIL_LIMIT)
            .toList());
    }

    @Override
    public List<StaffTicketDetail> findTicketDetails(String username, Instant startDate) {
        Condition replies = repliedBy(username, startDate);
        Field<Instant> lastReply = DSL.max(TICKET_REPLIES.CREATED_AT);
        return unitOfWork.report(() -> dsl
            .select(TICKETS.ID, TICKETS.SUBJECT, TICKETS.TYPE, TICKETS.STATUS, TICKETS.CREATED_AT,
                DSL.min(TICKET_REPLIES.CREATED_AT), lastReply)
            .from(TICKET_REPLIES)
            .join(TICKETS).on(TICKETS.ID.eq(TICKET_REPLIES.TICKET_ID))
            .where(replies)
            .groupBy(TICKETS.ID, TICKETS.SUBJECT, TICKETS.TYPE, TICKETS.STATUS, TICKETS.CREATED_AT)
            .orderBy(lastReply.desc(), TICKETS.ID.desc())
            .limit(DETAIL_LIMIT)
            .fetch(row -> new StaffTicketDetail(row.value1(), row.value2(), TicketCategory.fromCanonicalId(row.value3()),
                TicketStatus.fromCanonicalId(row.value4()), row.value5(), row.value6(), row.value7())));
    }

    @Override
    public Map<Instant, Long> countPunishmentsByDay(Collection<String> usernames, @Nullable String staffId, Instant startDate) {
        Condition issued = issuedBy(usernames, staffId, startDate);
        return unitOfWork.report(() -> byBucketStart(GroupedCounts.byDay(dsl, PUNISHMENTS, PUNISHMENTS.ISSUED_AT, issued)));
    }

    @Override
    public Map<Instant, Long> countTicketResponsesByDay(String username, Instant startDate) {
        Condition replies = repliedBy(username, startDate);
        return unitOfWork.report(() -> byBucketStart(
            GroupedCounts.byDay(dsl, TICKET_REPLIES, TICKET_REPLIES.CREATED_AT, replies)));
    }

    @Override
    public Map<Integer, Long> countPunishmentsByType(Collection<String> usernames, @Nullable String staffId, Instant startDate) {
        Condition issued = issuedBy(usernames, staffId, startDate);
        return unitOfWork.report(() -> GroupedCounts.byKey(dsl, PUNISHMENTS, PUNISHMENTS.TYPE_ORDINAL, issued));
    }

    private static Map<Instant, Long> byBucketStart(List<TimeBucketCount> buckets) {
        Map<Instant, Long> counts = new LinkedHashMap<>();
        buckets.forEach(bucket -> counts.put(bucket.bucketStart(), bucket.count()));
        return counts;
    }

    private static Condition moderationAction() {
        Condition moderation = AUDIT_LOGS.LEVEL.eq(MODERATION_LEVEL);
        for (String word : MODERATION_WORDS) {
            moderation = moderation.or(FreeText.contains(AUDIT_LOGS.DESCRIPTION, word));
        }
        return moderation;
    }

    private static Condition issuedBy(Collection<String> usernames, @Nullable String staffId, Instant startDate) {
        List<String> names = usernames.stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();
        Condition issuer = ColumnLookups.matchesAny(PUNISHMENTS.ISSUER_NAME_LOWER, names);
        if (staffId != null) {
            issuer = issuer.or(ColumnLookups.matches(PUNISHMENTS.ISSUER_ID, staffId));
        }
        return PUNISHMENTS.ISSUED_AT.ge(startDate).and(issuer);
    }

    private static Condition repliedBy(String username, Instant startDate) {
        return staffRepliesSince(startDate)
            .and(ColumnLookups.matches(TICKET_REPLIES.AUTHOR_NAME_LOWER, username.toLowerCase(Locale.ROOT)));
    }

    private static Condition staffRepliesSince(Instant startDate) {
        return TICKET_REPLIES.IS_STAFF.eq(true).and(TICKET_REPLIES.CREATED_AT.ge(startDate));
    }
}
