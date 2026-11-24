package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.IP_LOOKUPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.jooq.tables.PlayerIps;
import gg.modl.backend.iplookup.model.VpnStatus;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

final class CleanLinkEvidence {
    private CleanLinkEvidence() {
    }

    static Condition currentlyShared(String playerId, Field<String> otherPlayerId, int sharedAccountLimit) {
        PlayerIps mine = PLAYER_IPS.as("evidence_mine");
        PlayerIps theirs = PLAYER_IPS.as("evidence_theirs");
        PlayerIps sharer = PLAYER_IPS.as("evidence_sharer");
        Field<Integer> sharers = DSL.field(DSL.selectCount().from(sharer).where(sharer.IP.eq(mine.IP)));
        return DSL.exists(DSL.selectOne()
            .from(mine)
            .join(theirs).on(theirs.IP.eq(mine.IP))
            .join(IP_LOOKUPS).on(IP_LOOKUPS.IP.eq(mine.IP))
            .where(ColumnLookups.matches(mine.PLAYER_ID, playerId))
            .and(theirs.PLAYER_ID.eq(otherPlayerId))
            .and(mine.IS_PROXY.isFalse())
            .and(theirs.IS_PROXY.isFalse())
            .and(IP_LOOKUPS.VPN.eq(VpnStatus.NO.name()))
            .and(sharers.le(sharedAccountLimit)));
    }
}
