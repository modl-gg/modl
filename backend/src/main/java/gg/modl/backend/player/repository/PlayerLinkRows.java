package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.IP_LOOKUPS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.PlayerIps;
import gg.modl.backend.database.jooq.tables.PlayerLinks;
import gg.modl.backend.database.jooq.tables.records.PlayerLinksRecord;
import gg.modl.backend.iplookup.model.VpnStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

final class PlayerLinkRows {
    private static final Comparator<PlayerLinksRecord> KEY_ORDER = Comparator
        .comparing(PlayerLinksRecord::getPlayerId)
        .thenComparing(PlayerLinksRecord::getLinkedPlayerId);
    private static final String CLEAN = VpnStatus.NO.name();
    private static final String FLAGGED = VpnStatus.YES.name();
    private static final Integer FLAG_SET = 1;

    private final DSLContext dsl;
    private final InsertIfAbsent insertIfAbsent;
    private final PlayerWriter writer;

    PlayerLinkRows(DSLContext dsl, InsertIfAbsent insertIfAbsent, PlayerWriter writer) {
        this.dsl = dsl;
        this.insertIfAbsent = insertIfAbsent;
        this.writer = writer;
    }

    List<AddressShare> addressShares(String playerId) {
        PlayerIps mine = PLAYER_IPS.as("m");
        PlayerIps sharer = PLAYER_IPS.as("s");
        Field<Integer> accounts = DSL.count(sharer.PLAYER_ID).as("accounts");
        Field<String> vpn = DSL.max(IP_LOOKUPS.VPN).as("vpn");
        Field<Integer> knownProxy = DSL.max(DSL.when(mine.IS_PROXY.isTrue(), DSL.inline(1)).otherwise(DSL.inline(0)))
            .as("known_proxy");
        return dsl.select(mine.IP, accounts, vpn, knownProxy)
            .from(mine)
            .join(sharer).on(sharer.IP.eq(mine.IP))
            .leftJoin(IP_LOOKUPS).on(IP_LOOKUPS.IP.eq(mine.IP))
            .where(mine.PLAYER_ID.eq(playerId))
            .groupBy(mine.IP)
            .orderBy(mine.IP)
            .fetch(row -> new AddressShare(row.value1(), row.value2(),
                vpnStatus(row.value3(), FLAG_SET.equals(row.value4()))));
    }

    List<LinkCandidate> findLinkCandidates(String playerId, Collection<String> addresses, int limit) {
        if (addresses.isEmpty()) {
            return List.of();
        }
        PlayerIps other = PLAYER_IPS.as("o");
        PlayerLinks linked = PLAYER_LINKS.as("l");
        Condition cleanAddress = IP_LOOKUPS.VPN.eq(CLEAN);
        Field<Integer> clean = DSL.max(DSL.when(cleanAddress, DSL.inline(1)).otherwise(DSL.inline(0)));
        Condition notLinkedYet = linked.PLAYER_ID.isNull();
        Condition upgradable = linked.SHARED_CLEAN_ADDRESS.isFalse().and(cleanAddress);
        List<LinkCandidate> perChunk = InClauseChunks.collect(addresses, chunk -> dsl.select(other.PLAYER_ID, clean)
            .from(other)
            .leftJoin(IP_LOOKUPS).on(IP_LOOKUPS.IP.eq(other.IP))
            .leftJoin(linked).on(linked.PLAYER_ID.eq(playerId).and(linked.LINKED_PLAYER_ID.eq(other.PLAYER_ID)))
            .where(other.IP.in(chunk))
            .and(other.PLAYER_ID.ne(playerId))
            .and(notLinkedYet.or(upgradable))
            .groupBy(other.PLAYER_ID)
            .orderBy(other.PLAYER_ID)
            .limit(limit)
            .fetch(row -> new LinkCandidate(row.value1(), FLAG_SET.equals(row.value2()))));
        Map<String, Boolean> merged = mergeEvidence(perChunk);
        List<LinkCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : merged.entrySet()) {
            if (candidates.size() == limit) {
                break;
            }
            candidates.add(new LinkCandidate(entry.getKey(), entry.getValue()));
        }
        return candidates;
    }

    List<String> findPlayerIdsByAddress(String ip, int limit) {
        return dsl.select(PLAYER_IPS.PLAYER_ID)
            .from(PLAYER_IPS)
            .where(PLAYER_IPS.IP.eq(ip))
            .orderBy(PLAYER_IPS.PLAYER_ID)
            .limit(limit)
            .fetch(PLAYER_IPS.PLAYER_ID);
    }

    int link(String playerId, Collection<LinkCandidate> candidates, Instant now) {
        Map<String, Boolean> others = mergeEvidence(candidates);
        others.remove(playerId);
        if (others.isEmpty()) {
            return 0;
        }
        Set<String> participants = new TreeSet<>(others.keySet());
        participants.add(playerId);
        writer.lockInKeyOrder(participants);

        List<PlayerLinksRecord> pairs = new ArrayList<>();
        others.forEach((other, clean) -> {
            pairs.add(pair(playerId, other, now, clean));
            pairs.add(pair(other, playerId, now, clean));
        });
        pairs.sort(KEY_ORDER);

        Set<String> gained = new TreeSet<>();
        Set<String> newlyLinked = new TreeSet<>();
        InsertIfAbsent.Target<PlayerLinksRecord> target = insertIfAbsent.onPrimaryKey(PLAYER_LINKS);
        for (PlayerLinksRecord pair : pairs) {
            String counterpart = playerId.equals(pair.getPlayerId()) ? pair.getLinkedPlayerId() : pair.getPlayerId();
            if (target.insert(pair)) {
                gained.add(pair.getPlayerId());
                newlyLinked.add(counterpart);
            } else if (Boolean.TRUE.equals(pair.getSharedCleanAddress()) && confirmClean(pair)) {
                gained.add(pair.getPlayerId());
            }
        }
        InClauseChunks.sum(gained, chunk -> dsl.update(PLAYERS)
            .set(PLAYERS.LAST_LINKED_UPDATE_AT, now)
            .where(PLAYERS.ID.in(chunk))
            .execute());
        return newlyLinked.size();
    }

    private static Map<String, Boolean> mergeEvidence(Collection<LinkCandidate> candidates) {
        Map<String, Boolean> merged = new TreeMap<>();
        candidates.forEach(candidate -> merged.merge(candidate.playerId(), candidate.sharedCleanAddress(), Boolean::logicalOr));
        return merged;
    }

    private boolean confirmClean(PlayerLinksRecord pair) {
        return dsl.update(PLAYER_LINKS)
            .set(PLAYER_LINKS.SHARED_CLEAN_ADDRESS, true)
            .where(PLAYER_LINKS.PLAYER_ID.eq(pair.getPlayerId()))
            .and(PLAYER_LINKS.LINKED_PLAYER_ID.eq(pair.getLinkedPlayerId()))
            .and(PLAYER_LINKS.SHARED_CLEAN_ADDRESS.isFalse())
            .execute() > 0;
    }

    private static VpnStatus vpnStatus(String stored, boolean knownProxy) {
        if (knownProxy || FLAGGED.equals(stored)) {
            return VpnStatus.YES;
        }
        return CLEAN.equals(stored) ? VpnStatus.NO : VpnStatus.UNKNOWN;
    }

    private static PlayerLinksRecord pair(String playerId, String linkedPlayerId, Instant now, boolean sharedCleanAddress) {
        return new PlayerLinksRecord(
            TextLimits.require(PLAYER_LINKS.PLAYER_ID, playerId),
            TextLimits.require(PLAYER_LINKS.LINKED_PLAYER_ID, linkedPlayerId),
            now,
            sharedCleanAddress);
    }
}
