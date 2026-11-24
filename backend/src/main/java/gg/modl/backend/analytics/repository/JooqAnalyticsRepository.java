package gg.modl.backend.analytics.repository;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.IP_LOOKUPS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.TICKETS;

import gg.modl.backend.database.Sequencer;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.iplookup.repository.AddressVpnStatus;
import gg.modl.backend.ticket.data.TicketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record4;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqAnalyticsRepository implements AnalyticsRepository {
    static final int TOP_COUNTRIES = 20;

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    @Override
    public Map<String, Long> countTicketsByStatus(Instant startDate) {
        return unitOfWork.report(() -> GroupedCounts.byKey(dsl, TICKETS, TICKETS.STATUS, submittedSince(startDate)));
    }

    @Override
    public Map<String, Long> countTicketsByCategory(Instant startDate) {
        return unitOfWork.report(() -> GroupedCounts.byKey(dsl, TICKETS, TICKETS.TYPE, submittedSince(startDate)));
    }

    @Override
    public List<TimeBucketCount> countTicketsByDay(Instant startDate) {
        return unitOfWork.report(() -> GroupedCounts.byDay(dsl, TICKETS, TICKETS.CREATED_AT, submittedSince(startDate)));
    }

    @Override
    public List<CategoryResolution> averageResolutionByCategory(Instant startDate) {
        Field<Long> resolution = TICKETS.UPDATED_AT.coerce(Long.class).minus(TICKETS.CREATED_AT.coerce(Long.class));
        return unitOfWork.report(() -> dsl.select(TICKETS.TYPE, DSL.avg(resolution), DSL.count())
            .from(TICKETS)
            .where(TICKETS.STATUS.eq(TicketStatus.CLOSED.getId()))
            .and(TICKETS.UPDATED_AT.ge(startDate))
            .groupBy(TICKETS.TYPE)
            .orderBy(TICKETS.TYPE)
            .fetch(row -> new CategoryResolution(row.value1(), row.value2().doubleValue(), row.value3())));
    }

    @Override
    public PunishmentBreakdown loadPunishmentBreakdown(Instant startDate) {
        Condition issued = PUNISHMENTS.ISSUED_AT.ge(startDate);
        return unitOfWork.report(() -> new PunishmentBreakdown(
            GroupedCounts.byKey(dsl, PUNISHMENTS, PUNISHMENTS.TYPE_ORDINAL, issued),
            GroupedCounts.byDay(dsl, PUNISHMENTS, PUNISHMENTS.ISSUED_AT, issued)));
    }

    @Override
    public Map<String, Long> countAuditLogsByLevel(Instant startDate) {
        return unitOfWork.report(() -> GroupedCounts.byKey(dsl, AUDIT_LOGS, AUDIT_LOGS.LEVEL, loggedSince(startDate)));
    }

    @Override
    public List<TimeBucketCount> countAuditLogsByHour(Instant since) {
        return unitOfWork.report(() -> GroupedCounts.byHour(dsl, AUDIT_LOGS, AUDIT_LOGS.CREATED_AT, loggedSince(since)));
    }

    @Override
    public PlayerActivity loadPlayerActivity(Instant startDate) {
        return unitOfWork.report(() -> {
            List<TimeBucketCount> newPlayers =
                GroupedCounts.byDay(dsl, PLAYERS, PLAYERS.FIRST_JOIN_AT, PLAYERS.FIRST_JOIN_AT.ge(startDate));
            Map<String, Long> byCountry = countLoginsByCountry(startDate);
            Field<String> vpn = AddressVpnStatus.of(PLAYER_IPS.IS_PROXY);
            Record4<Integer, Integer, Integer, Integer> flagged = dsl
                .select(
                    countWhere(vpn.eq(DSL.inline(VpnStatus.YES.name()))),
                    countWhere(vpn.eq(DSL.inline(VpnStatus.NO.name()))),
                    countWhere(vpn.eq(DSL.inline(VpnStatus.UNKNOWN.name()))),
                    countWhere(PLAYER_IPS.IS_HOSTING.eq(DSL.inline(true))))
                .from(PLAYER_IPS)
                .leftJoin(IP_LOOKUPS).on(AddressVpnStatus.lookupOf(PLAYER_IPS.IP))
                .where(PLAYER_IPS.LAST_SEEN_AT.ge(startDate))
                .fetchSingle();
            PlayerActivity.VpnCounts vpnCounts = new PlayerActivity.VpnCounts(flagged.value1(), flagged.value2(), flagged.value3());
            return new PlayerActivity(newPlayers, byCountry, vpnCounts, flagged.value4());
        });
    }

    private static Field<Integer> countWhere(Condition condition) {
        return DSL.count(DSL.when(condition, DSL.inline(1)));
    }

    private Map<String, Long> countLoginsByCountry(Instant startDate) {
        Field<Long> loginsInWindow = DSL.when(PLAYER_IPS.FIRST_SEEN_AT.ge(startDate), PLAYER_IPS.LOGIN_COUNT)
            .otherwise(DSL.inline(1L));
        Field<BigDecimal> logins = DSL.sum(loginsInWindow).as("country_logins");
        Map<String, Long> byCountry = new LinkedHashMap<>();
        dsl.select(PLAYER_IPS.COUNTRY, logins)
            .from(PLAYER_IPS)
            .where(PLAYER_IPS.LAST_SEEN_AT.ge(startDate))
            .and(PLAYER_IPS.COUNTRY.isNotNull())
            .and(PLAYER_IPS.COUNTRY.ne(""))
            .groupBy(PLAYER_IPS.COUNTRY)
            .orderBy(logins.desc(), PLAYER_IPS.COUNTRY)
            .limit(TOP_COUNTRIES)
            .fetch()
            .forEach(row -> byCountry.put(row.value1(), row.value2().longValue()));
        return byCountry;
    }

    private static Condition submittedSince(Instant startDate) {
        return TICKETS.STATUS.ne(TicketStatus.UNFINISHED.getId()).and(TICKETS.CREATED_AT.ge(startDate));
    }

    private static Condition loggedSince(Instant startDate) {
        return AUDIT_LOGS.ID.ge(Sequencer.floorAt(startDate)).and(AUDIT_LOGS.CREATED_AT.ge(startDate));
    }
}
