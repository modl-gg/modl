package gg.modl.minecraft.core.imports.bansystem;

import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.Removal;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.BatchedPlayers;
import gg.modl.minecraft.core.imports.sql.SqlSession;
import gg.modl.minecraft.core.imports.support.InventoryCounter;
import gg.modl.minecraft.core.imports.support.KnownNames;
import gg.modl.minecraft.core.imports.support.LocalTimes;
import gg.modl.minecraft.core.imports.support.PlayerHistory;
import gg.modl.minecraft.core.imports.support.PlayerKeySet;
import gg.modl.minecraft.core.imports.support.PlayerKeys;
import gg.modl.minecraft.core.imports.support.PlayerLimit;
import gg.modl.minecraft.core.imports.support.SourceSupport;
import gg.modl.minecraft.core.util.Constants;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BanSystemReader implements SourceReader {
    private static final String CONSOLE = "CONSOLE";
    private static final String MUTE_TYPE = "CHAT";
    private static final long PERMANENT = -1;
    private static final String UNBANS = "unbans";
    private static final String[] PLAYER_TABLES = {"banhistories", "bans", "kicks", UNBANS};

    private final SqlSession session;
    private final SourceContext context;
    private final KnownNames knownNames;
    private final boolean indexable;
    private final ZoneId zone = ZoneId.systemDefault();
    private @Nullable Keys keys;

    BanSystemReader(SqlSession session, SourceContext context, KnownNames knownNames, boolean indexable) {
        this.session = session;
        this.context = context;
        this.knownNames = knownNames;
        this.indexable = indexable;
    }

    @Override
    public SourceInventory inventory() throws SourceException {
        Keys found = keys();
        return found.counts.toInventory(found.playerCount);
    }

    @Override
    public long historyEntriesBefore(long epochMillis) {
        return 0;
    }

    @Override
    public PlayerRecords players() throws SourceException {
        Keys found = keys();
        return new BatchedPlayers(BatchedPlayers.Keys.of(found.players), players -> batch(found, players),
                () -> session.query("SELECT 1 FROM bans WHERE 1 = 0", row -> { }));
    }

    private Keys keys() throws SourceException {
        Keys found = keys;
        if (found != null) return found;
        try {
            found = new Keys();
            for (String table : PLAYER_TABLES) {
                if (!session.hasTable(table)) continue;
                found.tables.add(table);
                if (indexable) index(table);
            }
            if (found.tables.contains("banhistories")) {
                PlayerLimit.require(session.count("SELECT COUNT(DISTINCT player) FROM banhistories"));
            }
            countBans(found);
            found.playerCount = found.players.size();
            if (session.hasColumn(UNBANS, "reason")) found.unbanReasons = true;
            if (session.hasTable("bedrockplayer")) {
                session.query("SELECT username, uuid FROM bedrockplayer", row -> {
                    String uuid = PlayerKeys.uuidOrNull(row.getString(2));
                    if (uuid != null && row.getString(1) != null) knownNames.remember(uuid, row.getString(1));
                });
            }
        } catch (SQLException failure) {
            throw SourceException.failed(failure);
        }
        keys = found;
        return found;
    }

    private void index(String table) {
        try {
            session.execute("CREATE INDEX modl_import_" + table + " ON " + table + " (player)");
        } catch (SQLException alreadyIndexed) {
            context.getLogger().debug("The BanSystem copy keeps its own index on " + table + ": " + alreadyIndexed.getMessage());
        }
    }

    private void countBans(Keys found) throws SQLException {
        BanIdentities history = new BanIdentities();
        if (found.tables.contains("banhistories")) {
            session.query("SELECT id, player, type FROM banhistories", row -> {
                String uuid = countBan(found, row);
                history.add(BanIdentities.of(row.getString(1), uuid));
            });
        }
        history.seal();
        if (found.tables.contains("bans")) {
            session.query("SELECT id, player, type FROM bans", row -> {
                if (!history.contains(BanIdentities.of(row.getString(1), PlayerKeys.uuidOrNull(row.getString(2))))) {
                    countBan(found, row);
                }
            });
        }
        if (!found.tables.contains("kicks")) return;
        session.query("SELECT player FROM kicks", row -> {
            found.counts.counted(PunishmentKind.KICK);
            String uuid = PlayerKeys.uuidOrNull(row.getString(1));
            if (uuid == null) found.counts.withoutAccount();
            else found.players.add(uuid);
        });
    }

    private static @Nullable String countBan(Keys found, ResultSet row) throws SQLException {
        String uuid = PlayerKeys.uuidOrNull(row.getString(2));
        found.counts.counted(MUTE_TYPE.equalsIgnoreCase(row.getString(3)) ? PunishmentKind.MUTE : PunishmentKind.BAN);
        if (uuid == null) found.counts.withoutAccount();
        else found.players.add(uuid);
        return uuid;
    }

    private Map<String, SourcePlayer> batch(Keys found, List<String> players) throws SQLException {
        String in = " WHERE player IN (" + SqlSession.placeholders(players.size()) + ")";
        SqlSession.Binder binder = SqlSession.bindAll(players);
        Map<String, Unban> unbans = new HashMap<>();
        if (found.tables.contains(UNBANS)) {
            session.query("SELECT id, player, unbanner, creationdate, " + (found.unbanReasons ? "reason" : "NULL")
                    + " FROM " + UNBANS + in, binder, row -> unbans.put(row.getString(1) + "|" + PlayerKeys.uuidOrNull(row.getString(2)),
                    new Unban(row.getString(3), LocalTimes.local(row.getString(4), zone), row.getString(5))));
        }
        Map<String, Ban> bans = new LinkedHashMap<>();
        Set<String> active = new HashSet<>();
        for (String table : new String[]{"banhistories", "bans"}) {
            if (!found.tables.contains(table)) continue;
            session.query("SELECT id, player, duration, creator, reason, ip, type, creationdate FROM " + table + in, binder, row -> {
                Ban ban = ban(row);
                bans.putIfAbsent(ban.key(), ban);
                if (table.equals("bans")) active.add(ban.key());
            });
        }
        Map<String, PlayerHistory> histories = new LinkedHashMap<>();
        for (String player : players) histories.put(player, new PlayerHistory());
        for (Ban ban : bans.values()) {
            PlayerHistory history = ban.uuid == null ? null : histories.get(ban.uuid);
            if (history != null) add(history, ban, active.contains(ban.key()), unbans.get(ban.key()));
        }
        if (found.tables.contains("kicks")) {
            Map<String, Integer> occurrences = new HashMap<>();
            session.query("SELECT player, creator, reason, creationdate FROM kicks" + in, binder, row -> {
                PlayerHistory history = histories.get(PlayerKeys.uuidOrNull(row.getString(1)));
                if (history != null) history.punished(kick(row, occurrences));
            });
        }
        Map<String, SourcePlayer> loaded = new HashMap<>();
        long now = context.now();
        histories.forEach((uuid, history) -> {
            String name = knownNames.nameOf(uuid);
            if (name != null) history.seen(name, null, now);
            loaded.put(uuid, history.toPlayer(uuid));
        });
        return loaded;
    }

    private Ban ban(ResultSet row) throws SQLException {
        return Ban.builder()
                .id(row.getString(1))
                .uuid(PlayerKeys.uuidOrNull(row.getString(2)))
                .duration((long) row.getDouble(3))
                .creator(row.getString(4))
                .reason(row.getString(5))
                .ip(PlayerKeys.addressOrNull(row.getString(6)))
                .mute(MUTE_TYPE.equalsIgnoreCase(row.getString(7)))
                .created(LocalTimes.local(row.getString(8), zone))
                .build();
    }

    private void add(PlayerHistory history, Ban ban, boolean active, @Nullable Unban unban) {
        PunishmentKind kind = ban.mute ? PunishmentKind.MUTE : PunishmentKind.BAN;
        long now = context.now();
        long issued = ban.created != null ? ban.created : now;
        boolean permanent = ban.duration == PERMANENT || ban.duration <= 0;
        long end = permanent ? Long.MAX_VALUE : issued + ban.duration;
        boolean lapsed = end <= now;
        Long unbannedAt = unban == null ? null : unban.at;
        boolean ranOut = unbannedAt != null && !permanent && unbannedAt >= end;
        Removal removal = unban != null && !ranOut
                ? new Removal(issuer(unban.by), unban.at, SourceSupport.trimmedOrNull(unban.reason))
                : null;
        history.seen(null, ban.ip, issued);
        history.punished(SourcePunishment.builder()
                .id(idOf(ban))
                .kind(kind)
                .reason(SourceSupport.textOr(ban.reason, context.getDefaultReason().get()))
                .issuerName(issuer(ban.creator))
                .issuedAt(issued)
                .duration(permanent ? 0 : ban.duration)
                .removal(removal)
                .inForce(active && !lapsed && removal == null)
                .endedEarly(!active && !lapsed && unban == null)
                .build());
    }

    private SourcePunishment kick(ResultSet row, Map<String, Integer> occurrences) throws SQLException {
        String uuid = String.valueOf(PlayerKeys.uuidOrNull(row.getString(1)));
        Long created = LocalTimes.local(row.getString(4), zone);
        long issued = created != null ? created : context.now();
        String fields = uuid + "|" + issued + "|" + row.getString(2) + "|" + row.getString(3);
        int occurrence = occurrences.merge(fields, 1, Integer::sum);
        String identity = fields + "|" + occurrence;
        return SourcePunishment.builder()
                .id("bansystem-kick-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)))
                .kind(PunishmentKind.KICK)
                .reason(SourceSupport.textOr(row.getString(3), context.getDefaultReason().get()))
                .issuerName(issuer(row.getString(2)))
                .issuedAt(issued)
                .build();
    }

    private String issuer(@Nullable String creator) {
        if (creator == null || CONSOLE.equalsIgnoreCase(creator.trim())) return Constants.DEFAULT_CONSOLE_NAME;
        String uuid = PlayerKeys.uuidOrNull(creator);
        if (uuid == null) return SourceSupport.issuer(creator);
        String name = knownNames.nameOf(uuid);
        return name != null ? name : uuid;
    }

    private static String idOf(Ban ban) {
        String created = ban.created == null ? "0" : Long.toString(ban.created, Character.MAX_RADIX);
        String id = ban.id == null ? "none" : ban.id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return "bansystem-" + (id.length() > 16 ? id.substring(0, 16) : id) + "-" + created;
    }

    @Override
    public boolean isReusable() {
        return session.isReusable();
    }

    @Override
    public void cancelRunning() {
        session.cancelRunning();
    }

    @Override
    public void close() {
        session.close();
    }

    @Value
    @Builder
    private static class Ban {
        @Nullable String id;
        @Nullable String uuid;
        long duration;
        @Nullable String creator;
        @Nullable String reason;
        @Nullable String ip;
        boolean mute;
        @Nullable Long created;

        String key() {
            return id + "|" + uuid;
        }
    }

    @Value
    private static class Unban {
        @Nullable String by;
        @Nullable Long at;
        @Nullable String reason;
    }

    private static final class Keys {
        private final PlayerKeySet players = new PlayerKeySet();
        private int playerCount;
        private final Set<String> tables = new HashSet<>();
        private final InventoryCounter counts = new InventoryCounter();
        private boolean unbanReasons;
    }

    private static final class BanIdentities {
        private static final long FNV_OFFSET = 0xcbf29ce484222325L;
        private static final long FNV_PRIME = 0x100000001b3L;
        private static final int INITIAL = 1024;

        private long[] hashes = new long[INITIAL];
        private int size;

        static long of(@Nullable String id, @Nullable String uuid) {
            long hash = FNV_OFFSET;
            for (char character : (id + "|" + uuid).toCharArray()) {
                hash ^= character;
                hash *= FNV_PRIME;
            }
            return hash;
        }

        void add(long hash) {
            if (size == hashes.length) hashes = Arrays.copyOf(hashes, size * 2);
            hashes[size++] = hash;
        }

        void seal() {
            Arrays.sort(hashes, 0, size);
        }

        boolean contains(long hash) {
            return Arrays.binarySearch(hashes, 0, size, hash) >= 0;
        }
    }
}
