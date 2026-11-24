package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.STAFF;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.PrefixRange;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.jooq.tables.records.PlayerNotificationsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerSessions;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Row2;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class JooqPlayerRepository implements PlayerRepository {
    static final int CONTAINS_SEARCH_MIN_LENGTH = 3;

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;
    private final JsonColumns json;
    private final Clock clock;
    private final PlayerReader reader;
    private final PlayerWriter writer;
    private final PlayerSightings sightings;
    private final PlayerLinkRows links;

    public JooqPlayerRepository(DSLContext dsl, UnitOfWork unitOfWork, InsertIfAbsent insertIfAbsent, Upserts upserts,
                                ChildRows childRows, JsonColumns json, Clock clock) {
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
        this.insertIfAbsent = insertIfAbsent;
        this.json = json;
        this.clock = clock;
        this.reader = new PlayerReader(dsl, json);
        this.writer = new PlayerWriter(insertIfAbsent, upserts, childRows);
        this.sightings = new PlayerSightings(dsl, upserts, writer);
        this.links = new PlayerLinkRows(dsl, insertIfAbsent, writer);
    }

    @Override
    public Optional<Player> findByMinecraftUuid(String uuid, PlayerParts... parts) {
        return snapshot(parts, () -> headerByUuid(uuid).flatMap(header -> assemble(List.of(header), parts).stream().findFirst()));
    }

    @Override
    public List<Player> findByMinecraftUuids(Collection<String> uuids, PlayerParts... parts) {
        List<String> normalised = uuids.stream().map(PlayerColumns::uuid).toList();
        return snapshot(parts, () -> assembleInIdOrder(InClauseChunks.collect(normalised,
            chunk -> dsl.selectFrom(PLAYERS).where(PLAYERS.MINECRAFT_UUID.in(chunk)).fetch()), parts));
    }

    @Override
    public List<Player> findByIds(Collection<String> playerIds, PlayerParts... parts) {
        return snapshot(parts, () -> assembleInIdOrder(InClauseChunks.collect(playerIds,
            chunk -> dsl.selectFrom(PLAYERS).where(PLAYERS.ID.in(chunk)).fetch()), parts));
    }

    @Override
    public Optional<Player> findByUsernameIgnoreCase(String username, PlayerParts... parts) {
        String key = UsernameEntry.lookupKey(username);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return snapshot(parts, () -> dsl.selectFrom(PLAYERS)
            .where(PLAYERS.ID.in(dsl.select(PLAYER_USERNAMES.PLAYER_ID)
                .from(PLAYER_USERNAMES)
                .where(PLAYER_USERNAMES.USERNAME_LOWER.eq(key))))
            .orderBy(
                DSL.when(PLAYERS.USERNAME_LOWER.eq(key), 0).otherwise(1),
                PLAYERS.IS_ONLINE.desc(),
                PLAYERS.LAST_LOGIN_AT.desc().nullsLast(),
                PLAYERS.MINECRAFT_UUID)
            .limit(1)
            .fetchOptional()
            .flatMap(header -> assemble(List.of(header), parts).stream().findFirst()));
    }

    @Override
    public AddressSharers findRecentByIps(Collection<String> ips, @Nullable String excludedPlayerId, int limit) {
        Condition notExcluded = excludedPlayerId == null ? DSL.noCondition() : PLAYER_IPS.PLAYER_ID.ne(excludedPlayerId);
        Field<Instant> lastSeen = DSL.max(PLAYER_IPS.LAST_SEEN_AT);
        Map<String, Instant> lastSeenByPlayer = new HashMap<>();
        InClauseChunks.collect(ips, chunk -> dsl.select(PLAYER_IPS.PLAYER_ID, lastSeen)
                .from(PLAYER_IPS)
                .where(PLAYER_IPS.IP.in(chunk))
                .and(notExcluded)
                .groupBy(PLAYER_IPS.PLAYER_ID)
                .orderBy(lastSeen.desc(), PLAYER_IPS.PLAYER_ID)
                .limit(limit + 1)
                .fetch())
            .forEach(row -> lastSeenByPlayer.merge(row.value1(), row.value2(), (first, second) -> first.isAfter(second) ? first : second));
        List<String> ranked = lastSeenByPlayer.entrySet().stream()
            .sorted(Map.Entry.<String, Instant>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .toList();
        List<String> shown = ranked.subList(0, Math.min(limit, ranked.size()));
        Map<String, Player> byId = findByIds(shown).stream().collect(Collectors.toMap(Player::getId, Function.identity()));
        return new AddressSharers(shown.stream().map(byId::get).filter(Objects::nonNull).toList(), ranked.size() > limit);
    }

    @Override
    public List<Player> findOnline(@Nullable String afterPlayerId, int limit, PlayerParts... parts) {
        Condition afterCursor = afterPlayerId == null ? DSL.noCondition() : PLAYERS.ID.gt(afterPlayerId);
        return snapshot(parts, () -> assemble(dsl.selectFrom(PLAYERS)
            .where(PLAYERS.IS_ONLINE.eq(true))
            .and(afterCursor)
            .orderBy(PLAYERS.ID)
            .limit(limit)
            .fetch(), parts));
    }

    @Override
    public Map<String, String> findOrCreateIdsByMinecraftUuid(Map<String, String> usernameByUuid, Instant now) {
        Map<String, String> requested = new HashMap<>();
        Map<String, String> usernames = new TreeMap<>();
        usernameByUuid.forEach((raw, username) -> {
            String uuid = UuidUtils.dashed(raw);
            if (uuid != null) {
                requested.put(raw, uuid);
                usernames.put(uuid, username == null ? "" : username);
            }
        });
        Map<String, String> found = idsByUuid(usernames.keySet());
        Map<String, String> ids = found.size() == usernames.size()
            ? found
            : unitOfWork.write(() -> createMissing(usernames, now));
        Map<String, String> result = new HashMap<>();
        requested.forEach((raw, uuid) -> {
            String id = ids.get(uuid);
            if (id != null) {
                result.put(raw, id);
            }
        });
        return result;
    }

    @Override
    public boolean insertIfAbsent(Player player) {
        Instant now = clock.instant();
        return unitOfWork.write(() -> {
            String id = player.getId() != null ? player.getId() : EntityIds.next();
            if (!writer.insertHeader(PlayerRows.header(id, player, json, now))) {
                return false;
            }
            PlayerRows.usernames(id, player.getUsernames(), now).forEach(row -> dsl.insertInto(PLAYER_USERNAMES).set(row).execute());
            PlayerRows.addresses(id, player.getIpAddresses(), now).forEach(row -> dsl.insertInto(PLAYER_IPS).set(row).execute());
            if (player.getNotes() != null) {
                player.getNotes().forEach(note -> writer.appendNote(PlayerRows.note(id, note, now)));
            }
            return true;
        });
    }

    @Override
    public LoginRecord recordLogin(LoginUpdate update) {
        return unitOfWork.write(() -> sightings.recordLogin(update));
    }

    @Override
    public void recordUsername(String playerId, String username, Instant seenAt) {
        String current = PlayerColumns.storedUsername(username);
        unitOfWork.write(() -> {
            int renamed = dsl.update(PLAYERS)
                .set(PLAYERS.USERNAME, current)
                .set(PLAYERS.USERNAME_LOWER, PlayerColumns.fit(PLAYERS.USERNAME_LOWER, PlayerColumns.usernameKey(current)))
                .where(PLAYERS.ID.eq(playerId))
                .execute();
            if (renamed > 0) {
                writer.adoptUsername(playerId, current, seenAt);
            }
        });
    }

    @Override
    public void recordIp(String playerId, String ip, Instant seenAt) {
        String address = TextLimits.requireIfPresent(PLAYER_IPS.IP, PlayerColumns.address(ip));
        if (address != null) {
            unitOfWork.write(() -> sightings.recordAddress(playerId, address, null, seenAt));
        }
    }

    @Override
    public int updateIpGeoForAddress(String ip, IpGeo geo) {
        String address = PlayerColumns.storableAddress(ip);
        if (address == null) {
            return 0;
        }
        return unitOfWork.write(() -> dsl.update(PLAYER_IPS)
            .set(PLAYER_IPS.COUNTRY, PlayerColumns.fitOrNull(PLAYER_IPS.COUNTRY, geo.country()))
            .set(PLAYER_IPS.REGION, PlayerColumns.fitOrNull(PLAYER_IPS.REGION, geo.region()))
            .set(PLAYER_IPS.ASN, PlayerColumns.fitOrNull(PLAYER_IPS.ASN, geo.asn()))
            .set(PLAYER_IPS.IS_PROXY, geo.proxy())
            .set(PLAYER_IPS.IS_HOSTING, geo.hosting())
            .where(PLAYER_IPS.IP.eq(address))
            .execute());
    }

    @Override
    public boolean updateLastServer(String uuid, String serverName) {
        return unitOfWork.write(() -> dsl.update(PLAYERS)
            .set(PLAYERS.LAST_SERVER_NAME, PlayerColumns.fit(PLAYERS.LAST_SERVER_NAME, serverName))
            .where(PLAYERS.MINECRAFT_UUID.eq(PlayerColumns.uuid(uuid)))
            .execute() > 0);
    }

    @Override
    public void addNote(String playerId, NoteEntry note) {
        Instant now = clock.instant();
        unitOfWork.write(() -> writer.appendNote(PlayerRows.note(playerId, note, now)));
    }

    @Override
    public List<AddressShare> addressShares(String playerId) {
        return unitOfWork.read(() -> links.addressShares(playerId));
    }

    @Override
    public List<LinkCandidate> findLinkCandidates(String playerId, Collection<String> addresses, int limit) {
        List<String> storable = addresses.stream().filter(address -> TextLimits.canMatch(PLAYER_IPS.IP, address)).toList();
        return unitOfWork.read(() -> links.findLinkCandidates(playerId, storable, limit));
    }

    @Override
    public List<String> findPlayerIdsByAddress(String ip, int limit) {
        String address = PlayerColumns.storableAddress(ip);
        return address == null ? List.of() : unitOfWork.read(() -> links.findPlayerIdsByAddress(address, limit));
    }

    @Override
    public int link(String playerId, Collection<LinkCandidate> candidates, Instant now) {
        return unitOfWork.write(() -> links.link(playerId, candidates, now));
    }

    @Override
    public int linkUnverified(String playerId, Collection<String> linkedPlayerIds, Instant now) {
        return link(playerId, linkedPlayerIds.stream().map(LinkCandidate::unverified).toList(), now);
    }

    @Override
    public void addNotification(String playerId, PlayerNotification notification) {
        String id = notification.id() == null || notification.id().isBlank() ? EntityIds.next() : notification.id();
        PlayerNotificationsRecord row = new PlayerNotificationsRecord(
            TextLimits.require(PLAYER_NOTIFICATIONS.PLAYER_ID, playerId),
            TextLimits.require(PLAYER_NOTIFICATIONS.ID, id),
            json.write(notification.payload()),
            notification.createdAt(),
            notification.expiresAt());
        unitOfWork.write(() -> insertIfAbsent.onPrimaryKey(PLAYER_NOTIFICATIONS).insert(row));
    }

    @Override
    public int acknowledgeNotifications(String playerId, Collection<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        List<String> ordered = ids.stream().sorted().toList();
        return unitOfWork.write(() -> InClauseChunks.sum(ordered, chunk -> dsl.deleteFrom(PLAYER_NOTIFICATIONS)
            .where(PLAYER_NOTIFICATIONS.PLAYER_ID.eq(playerId))
            .and(PLAYER_NOTIFICATIONS.ID.in(chunk))
            .execute()));
    }

    @Override
    public Map<String, List<PlayerNotification>> findNotifications(Collection<String> playerIds, Instant now) {
        return reader.notifications(playerIds, now);
    }

    @Override
    public List<Player> search(String term, int limit) {
        String key = UsernameEntry.lookupKey(term);
        if (key.isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> matches = new LinkedHashSet<>();
        matches.addAll(usernameMatches(PLAYER_USERNAMES.USERNAME_LOWER.eq(key), limit));
        if (matches.size() < limit) {
            matches.addAll(usernameMatches(PrefixRange.of(PLAYER_USERNAMES.USERNAME_LOWER, key), limit));
        }
        if (matches.size() < limit && key.length() >= CONTAINS_SEARCH_MIN_LENGTH) {
            matches.addAll(unitOfWork.search(() -> usernameMatches(PLAYER_USERNAMES.USERNAME_LOWER.contains(key), limit)));
        }
        List<String> ranked = matches.stream().limit(limit).toList();
        Map<String, Player> players = findByIds(ranked, PlayerParts.USERNAMES).stream()
            .collect(Collectors.toMap(Player::getId, Function.identity()));
        return ranked.stream().filter(players::containsKey).map(players::get).toList();
    }

    private List<String> usernameMatches(Condition matching, int limit) {
        return dsl.select(PLAYER_USERNAMES.PLAYER_ID)
            .from(PLAYER_USERNAMES)
            .where(matching)
            .groupBy(PLAYER_USERNAMES.PLAYER_ID)
            .orderBy(DSL.min(PLAYER_USERNAMES.USERNAME_LOWER), PLAYER_USERNAMES.PLAYER_ID)
            .limit(limit)
            .fetch(PLAYER_USERNAMES.PLAYER_ID);
    }

    @Override
    public List<PlayerSummary> findAvailableForStaffAssignment(int limit) {
        return unitOfWork.report(() -> dsl.select(PLAYERS.ID, PLAYERS.MINECRAFT_UUID, PLAYERS.USERNAME)
            .from(PLAYERS)
            .where(DSL.notExists(dsl.selectOne()
                .from(STAFF)
                .where(STAFF.ASSIGNED_MINECRAFT_UUID.eq(PLAYERS.MINECRAFT_UUID))))
            .orderBy(PLAYERS.CREATED_AT, PLAYERS.ID)
            .limit(limit)
            .fetch(row -> new PlayerSummary(row.value1(), row.value2(), row.value3())));
    }

    @Override
    public boolean markOffline(String uuid, Instant now) {
        return unitOfWork.write(() -> endSessions(PLAYERS.MINECRAFT_UUID.eq(PlayerColumns.uuid(uuid)), now)) > 0;
    }

    @Override
    public boolean withdrawRefusedLogin(String playerId, Instant loggedInAt) {
        return unitOfWork.write(() -> dsl.update(PLAYERS)
            .set(PLAYERS.IS_ONLINE, false)
            .set(PLAYERS.LAST_LOGOUT_AT, loggedInAt)
            .where(PLAYERS.ID.eq(TextLimits.require(PLAYERS.ID, playerId)))
            .and(PLAYERS.IS_ONLINE.eq(true))
            .and(PLAYERS.LAST_LOGIN_AT.eq(loggedInAt))
            .execute()) > 0;
    }

    @Override
    public Set<String> findOnlineUuids(@Nullable String serverNameOrNull) {
        String serverName = PlayerColumns.fitOrNull(PLAYERS.LAST_SERVER_NAME, serverNameOrNull);
        Condition onServer = serverName == null ? DSL.noCondition() : PLAYERS.LAST_SERVER_NAME.eq(serverName);
        return Set.copyOf(dsl.select(PLAYERS.MINECRAFT_UUID)
            .from(PLAYERS)
            .where(PLAYERS.IS_ONLINE.eq(true))
            .and(onServer)
            .fetch(PLAYERS.MINECRAFT_UUID));
    }

    @Override
    public int markOfflineAll(Collection<String> uuids, Instant now) {
        if (uuids.isEmpty()) {
            return 0;
        }
        List<String> ordered = uuids.stream().map(PlayerColumns::uuid).sorted().toList();
        return unitOfWork.write(() -> InClauseChunks.sum(ordered, chunk -> endSessions(PLAYERS.MINECRAFT_UUID.in(chunk), now)));
    }

    private int endSessions(Condition players, Instant now) {
        int ended = 0;
        for (Record2<String, Instant> online : dsl.select(PLAYERS.ID, PLAYERS.LAST_LOGIN_AT)
            .from(PLAYERS)
            .where(players)
            .and(PLAYERS.IS_ONLINE.eq(true))
            .orderBy(PLAYERS.ID)
            .fetch()) {
            Instant joined = online.value2();
            long played = joined == null ? 0L : PlayerSessions.countedSeconds(Duration.between(joined, now));
            ended += dsl.update(PLAYERS)
                .set(PLAYERS.IS_ONLINE, false)
                .set(PLAYERS.LAST_LOGOUT_AT, now)
                .set(PLAYERS.TOTAL_PLAYTIME_SECONDS, PLAYERS.TOTAL_PLAYTIME_SECONDS.plus(played))
                .where(PLAYERS.ID.eq(online.value1()))
                .and(PLAYERS.IS_ONLINE.eq(true))
                .execute();
        }
        return ended;
    }

    @Override
    public Set<String> findActiveBanHolders(Collection<String> playerIds, Instant now) {
        return Set.copyOf(InClauseChunks.collect(playerIds, chunk -> dsl.selectDistinct(PUNISHMENTS.PLAYER_ID)
            .from(PUNISHMENTS)
            .where(PUNISHMENTS.PLAYER_ID.in(chunk))
            .and(PUNISHMENTS.ENFORCEMENT_CATEGORY.eq(ResolvedCategory.BAN.name()))
            .and(PUNISHMENTS.ACTIVE_UNTIL_AT.ge(now))
            .fetch(PUNISHMENTS.PLAYER_ID)));
    }

    @Override
    public Map<String, ActiveCounts> countActiveByPlayer(Collection<String> playerIds, Instant now) {
        Field<Integer> active = DSL.count();
        Map<String, ActiveCounts> counts = new HashMap<>();
        for (List<String> chunk : InClauseChunks.<String>of(playerIds)) {
            dsl.select(PUNISHMENTS.PLAYER_ID, PUNISHMENTS.ENFORCEMENT_CATEGORY, active)
                .from(PUNISHMENTS)
                .where(PUNISHMENTS.PLAYER_ID.in(chunk))
                .and(PUNISHMENTS.ACTIVE_UNTIL_AT.ge(now))
                .and(PUNISHMENTS.ENFORCEMENT_CATEGORY.in(ResolvedCategory.BAN.name(), ResolvedCategory.MUTE.name()))
                .groupBy(PUNISHMENTS.PLAYER_ID, PUNISHMENTS.ENFORCEMENT_CATEGORY)
                .fetch()
                .forEach(row -> counts.merge(row.value1(),
                    countsOf(ResolvedCategory.fromStored(row.value2()), row.value3()),
                    (left, right) -> new ActiveCounts(left.bans() + right.bans(), left.mutes() + right.mutes())));
        }
        return counts;
    }

    @Override
    public IpPruneBatch pruneIpsUnseenSince(Instant cutoff, Instant now, @Nullable IpHistoryKey after, int batchSize) {
        Condition prunable = prunable(cutoff, now);
        Condition resumed = after == null
            ? DSL.noCondition()
            : DSL.row(PLAYER_IPS.PLAYER_ID, PLAYER_IPS.IP).gt(after.playerId(), after.ip());
        List<IpHistoryKey> scanned = unitOfWork.read(() -> dsl.select(PLAYER_IPS.PLAYER_ID, PLAYER_IPS.IP)
            .from(PLAYER_IPS)
            .where(resumed)
            .and(prunable)
            .orderBy(PLAYER_IPS.PLAYER_ID, PLAYER_IPS.IP)
            .limit(batchSize)
            .fetch(row -> new IpHistoryKey(row.value1(), row.value2())));
        if (scanned.isEmpty()) {
            return new IpPruneBatch(0L, null);
        }
        List<Row2<String, String>> keys = scanned.stream().map(key -> DSL.row(key.playerId(), key.ip())).toList();
        long deleted = unitOfWork.write(() -> dsl.deleteFrom(PLAYER_IPS)
            .where(DSL.row(PLAYER_IPS.PLAYER_ID, PLAYER_IPS.IP).in(keys))
            .and(prunable)
            .execute());
        return new IpPruneBatch(deleted, scanned.size() < batchSize ? null : scanned.getLast());
    }

    private static Condition prunable(Instant cutoff, Instant now) {
        return PLAYER_IPS.LAST_SEEN_AT.lt(cutoff)
            .and(PLAYER_IPS.RETAINED_UNTIL_AT.isNull().or(PLAYER_IPS.RETAINED_UNTIL_AT.lt(now)));
    }

    private Map<String, String> createMissing(Map<String, String> usernames, Instant now) {
        Map<String, String> ids = idsByUuid(usernames.keySet());
        usernames.forEach((uuid, username) -> {
            if (ids.containsKey(uuid)) {
                return;
            }
            String id = EntityIds.next();
            if (writer.insertHeader(PlayerRows.minimal(id, uuid, username, now)) && !username.isBlank()
                && !PlayerColumns.UNKNOWN_USERNAME.equals(username)) {
                writer.adoptUsername(id, username, now);
            }
        });
        return idsByUuid(usernames.keySet());
    }

    private Map<String, String> idsByUuid(Collection<String> uuids) {
        Map<String, String> ids = new HashMap<>();
        for (List<String> chunk : InClauseChunks.<String>of(uuids)) {
            dsl.select(PLAYERS.MINECRAFT_UUID, PLAYERS.ID)
                .from(PLAYERS)
                .where(PLAYERS.MINECRAFT_UUID.in(chunk))
                .fetch()
                .forEach(row -> ids.put(row.value1(), row.value2()));
        }
        return ids;
    }

    private Optional<PlayersRecord> headerByUuid(String uuid) {
        return dsl.selectFrom(PLAYERS).where(PLAYERS.MINECRAFT_UUID.eq(PlayerColumns.uuid(uuid))).fetchOptional();
    }

    private <T> T snapshot(PlayerParts[] parts, Supplier<T> query) {
        return parts.length == 0 ? query.get() : unitOfWork.read(query);
    }

    private List<Player> assembleInIdOrder(List<PlayersRecord> headers, PlayerParts... parts) {
        return assemble(headers.stream().sorted(Comparator.comparing(PlayersRecord::getId)).toList(), parts);
    }

    private List<Player> assemble(List<PlayersRecord> headers, PlayerParts... parts) {
        return reader.assemble(headers, clock.instant(), parts);
    }

    private static ActiveCounts countsOf(ResolvedCategory category, int count) {
        return switch (category) {
            case BAN -> new ActiveCounts(count, 0L);
            case MUTE -> new ActiveCounts(0L, count);
            case NONE -> ActiveCounts.NONE;
        };
    }
}
