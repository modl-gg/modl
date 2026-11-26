package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.jooq.tables.Punishments;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.jooq.Condition;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.impl.DSL;

final class PunishmentConditions {
    private static final List<String> ENFORCEMENT_ENDING_TYPES = Arrays.stream(PunishmentModificationType.values())
        .filter(PunishmentModificationType::endsEnforcement)
        .map(Enum::name)
        .toList();

    private PunishmentConditions() {
    }

    static Condition activeAt(Instant now) {
        return PUNISHMENTS.ACTIVE_UNTIL_AT.ge(now);
    }

    static Condition inPhase(PunishmentPhase phase, Instant now) {
        return DSL.or(phaseBranches(phase, now));
    }

    static Condition inAnyPhase(Collection<PunishmentPhase> phases, Instant now) {
        return DSL.or(phases.stream().map(phase -> inPhase(phase, now)).toList());
    }

    static Condition notEnded(Instant now) {
        return inAnyPhase(PunishmentPhase.NOT_ENDED, now);
    }

    static List<Condition> listed(PunishmentListQuery query, Instant now) {
        Condition filters = listingFilters(query);
        if (query.phase() == null) {
            return List.of(filters);
        }
        return phaseBranches(query.phase(), now).stream().map(filters::and).toList();
    }

    private static List<Condition> phaseBranches(PunishmentPhase phase, Instant now) {
        return switch (phase) {
            case ACTIVE -> List.of(activeAt(now).and(PUNISHMENTS.STARTED_AT.isNotNull()));
            case UNSTARTED -> List.of(neverStarted(now), queuedAfterStart(now));
            case ENDED -> List.of(isKick().or(PUNISHMENTS.ACTIVE_UNTIL_AT.lt(now).and(DSL.or(
                PUNISHMENTS.STATUS.isNull(), PUNISHMENTS.STATUS.ne(PunishmentStatus.UNSTARTED), enforcementEnded()))));
        };
    }

    private static Condition neverStarted(Instant now) {
        return PUNISHMENTS.STARTED_AT.isNull().andNot(isKick()).and(activeAt(now).or(waitingToStart()));
    }

    private static Condition queuedAfterStart(Instant now) {
        return PUNISHMENTS.STARTED_AT.isNotNull().andNot(isKick())
            .and(PUNISHMENTS.ACTIVE_UNTIL_AT.lt(now)).and(waitingToStart());
    }

    private static Condition listingFilters(PunishmentListQuery query) {
        Condition listed = DSL.not(isKick());
        if (query.issuer() != null) {
            listed = listed.and(issuedBy(query.issuer()));
        }
        if (query.typeOrdinal() != null) {
            listed = listed.and(PUNISHMENTS.TYPE_ORDINAL.eq(query.typeOrdinal()));
        }
        return switch (query.evidence()) {
            case ANY -> listed;
            case WITH -> listed.andExists(evidenceOfListed());
            case WITHOUT -> listed.andNotExists(evidenceOfListed());
        };
    }

    static Condition enforcementRelevantAt(Instant now) {
        return DSL.or(
            activeAt(now),
            hasUnstartedStatus(),
            undeliveredKick(),
            PUNISHMENTS.WIPE_AFTER_EXPIRY.eq(true)
                .and(PUNISHMENTS.STAT_WIPE_COMPLETED_AT.isNull())
                .and(PUNISHMENTS.STARTED_AT.isNotNull()));
    }

    static Condition syncRelevant(Instant lastSync, Instant now) {
        return DSL.or(
            activeAt(now),
            PUNISHMENTS.MODIFIED_AT.gt(lastSync),
            PUNISHMENTS.ISSUED_AT.gt(lastSync),
            hasUnstartedStatus(),
            undeliveredKick());
    }

    private static Condition waitingToStart() {
        return hasUnstartedStatus().andNot(enforcementEnded());
    }

    private static Condition enforcementEnded() {
        return DSL.exists(DSL.selectOne()
            .from(PUNISHMENT_MODIFICATIONS)
            .where(PUNISHMENT_MODIFICATIONS.PUNISHMENT_ID.eq(PUNISHMENTS.ID))
            .and(PUNISHMENT_MODIFICATIONS.TYPE.in(ENFORCEMENT_ENDING_TYPES)));
    }

    static Condition queued(Punishments table) {
        return table.STATUS.eq(PunishmentStatus.UNSTARTED);
    }

    static Condition issuedBy(IssuerMatch issuer) {
        Condition byName = issuer.issuerName() == null ? DSL.falseCondition()
            : ColumnLookups.matches(PUNISHMENTS.ISSUER_NAME_LOWER, PunishmentRows.lowerCase(issuer.issuerName()));
        Condition byId = issuer.issuerId() == null ? DSL.falseCondition()
            : ColumnLookups.matches(PUNISHMENTS.ISSUER_ID, issuer.issuerId());
        return byName.or(byId);
    }

    private static Select<Record1<Integer>> evidenceOfListed() {
        return DSL.selectOne().from(PUNISHMENT_EVIDENCE).where(PUNISHMENT_EVIDENCE.PUNISHMENT_ID.eq(PUNISHMENTS.ID));
    }

    private static Condition hasUnstartedStatus() {
        return queued(PUNISHMENTS);
    }

    private static Condition undeliveredKick() {
        return isKick().and(PUNISHMENTS.STARTED_AT.isNull());
    }

    private static Condition isKick() {
        return PUNISHMENTS.TYPE_ORDINAL.eq(Punishment.KICK_TYPE_ORDINAL);
    }
}
