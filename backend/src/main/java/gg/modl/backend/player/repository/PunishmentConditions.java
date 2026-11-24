package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.jooq.tables.Punishments;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.jooq.Condition;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.impl.DSL;

final class PunishmentConditions {
    private static final int KICK_ORDINAL = 0;
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
        return switch (phase) {
            case ACTIVE -> activeAt(now).and(PUNISHMENTS.STARTED_AT.isNotNull());
            case UNSTARTED -> activeAt(now).and(PUNISHMENTS.STARTED_AT.isNull())
                .or(PUNISHMENTS.ACTIVE_UNTIL_AT.lt(now).and(waitingToStart()));
            case ENDED -> PUNISHMENTS.ACTIVE_UNTIL_AT.lt(now).and(DSL.or(
                PUNISHMENTS.STATUS.isNull(), PUNISHMENTS.STATUS.ne(PunishmentStatus.UNSTARTED), enforcementEnded()));
        };
    }

    static Condition listed(PunishmentListQuery query, Instant now) {
        Condition listed = PUNISHMENTS.TYPE_ORDINAL.ne(KICK_ORDINAL);
        if (query.phase() != null) {
            listed = listed.and(inPhase(query.phase(), now));
        }
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

    static Condition activeOrUnstartedAt(Instant now) {
        return activeAt(now).or(waitingToStart());
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
        return PUNISHMENTS.TYPE_ORDINAL.eq(KICK_ORDINAL).and(PUNISHMENTS.STARTED_AT.isNull());
    }
}
