package gg.modl.minecraft.core.imports.advancedban;

import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.source.UnreadablePlayerException;
import gg.modl.minecraft.core.imports.sql.BatchedPlayers;
import gg.modl.minecraft.core.imports.sql.SqlDialect;
import gg.modl.minecraft.core.imports.sql.SqlSession;
import gg.modl.minecraft.core.imports.support.InventoryCounter;
import gg.modl.minecraft.core.imports.support.KnownNames;
import gg.modl.minecraft.core.imports.support.PlayerHistory;
import gg.modl.minecraft.core.imports.support.PlayerKeySet;
import gg.modl.minecraft.core.imports.support.PlayerKeys;
import gg.modl.minecraft.core.imports.support.PlayerLimit;
import gg.modl.minecraft.core.imports.support.SourceSupport;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class AdvancedBanReader implements SourceReader {
    private static final long PERMANENT = -1;
    private static final String HISTORY = "PunishmentHistory";
    private static final String ACTIVE = "Punishments";
    private static final String IP_TYPES = "('IP_BAN', 'TEMP_IP_BAN')";

    private final SqlSession session;
    private final SourceContext context;
    private final KnownNames knownNames;
    private final boolean indexable;
    private @Nullable Keys keys;

    AdvancedBanReader(SqlSession session, SourceContext context, KnownNames knownNames, boolean indexable) {
        this.session = session;
        this.context = context;
        this.knownNames = knownNames;
        this.indexable = indexable;
    }

    @Override
    public SourceInventory inventory() throws SourceException {
        Keys found = keys();
        return found.counts.toInventory(found.players);
    }

    @Override
    public long historyEntriesBefore(long epochMillis) {
        return 0;
    }

    @Override
    public PlayerRecords players() throws SourceException {
        return new Players(keys());
    }

    private Keys keys() throws SourceException {
        Keys found = keys;
        if (found != null) return found;
        try {
            if (indexable) {
                session.execute("CREATE INDEX modl_import_uuid ON " + table(HISTORY) + " (" + column("uuid") + ", "
                        + column("id") + ")");
            }
        } catch (SQLException alreadyIndexed) {
            context.getLogger().debug("The AdvancedBan copy keeps its own index: " + alreadyIndexed.getMessage());
        }
        try {
            PlayerLimit.require(session.count("SELECT COUNT(DISTINCT " + column("uuid") + ") FROM " + table(HISTORY)
                    + " WHERE " + column("punishmentType") + " NOT IN " + IP_TYPES));
            found = readKeys();
        } catch (SQLException failure) {
            throw SourceException.failed(failure);
        }
        keys = found;
        return found;
    }

    private Keys readKeys() throws SQLException, SourceException {
        Keys found = new Keys();
        session.query("SELECT " + column("uuid") + ", " + column("start") + ", " + column("punishmentType") + " FROM "
                + table(ACTIVE), row -> found.active.add(activeKey(row.getString(1), row.getLong(2),
                AdvancedBanType.of(row.getString(3)))));
        Map<String, NameKey> nameKeys = new HashMap<>();
        session.query("SELECT " + column("uuid") + ", " + column("name") + ", " + column("punishmentType") + ", "
                + column("start") + " FROM " + table(HISTORY), row -> {
            AdvancedBanType type = AdvancedBanType.of(row.getString(3));
            String key = row.getString(1);
            if (type == null || isDeletedNote(found, type, key, row.getLong(4))) return;
            found.counts.counted(type.getKind());
            if (type.isAddressWide()) return;
            String uuid = PlayerKeys.uuidOrNull(key);
            if (uuid != null) {
                found.accounts.add(uuid);
                if (key != null && !uuid.replace("-", "").equals(key)) extraKey(found, uuid, key);
            } else if (key != null && SourceSupport.isPlayerName(key)) {
                nameKeys.computeIfAbsent(key, unseen -> new NameKey()).seen(row.getString(2));
            } else {
                found.counts.withoutAccount();
            }
        });
        List<Row> addressBans = new ArrayList<>();
        session.query(rowsQuery() + " WHERE " + column("punishmentType") + " IN " + IP_TYPES, row -> addressBans.add(row(row)));
        Map<String, String> accountsByName = accountsByName(nameKeys, addressBans);
        nameKeys.forEach((key, nameKey) -> {
            String account = accountOfName(key, nameKey.typed, accountsByName);
            if (account == null) {
                found.counts.withoutAccount(nameKey.rows);
                return;
            }
            found.accounts.add(account);
            extraKey(found, account, key);
        });
        for (Row ban : addressBans) addressBan(found, ban, accountsByName);
        found.players = found.accounts.size();
        return found;
    }

    private static void extraKey(Keys found, String account, String key) {
        found.extraKeysByAccount.computeIfAbsent(account, unseen -> new LinkedHashSet<>()).add(key);
    }

    private boolean isDeletedNote(Keys found, AdvancedBanType type, @Nullable String key, long start) {
        return type.getKind() == PunishmentKind.NOTE && !found.active.contains(activeKey(key, start, type));
    }

    private Map<String, String> accountsByName(Map<String, NameKey> nameKeys, List<Row> addressBans) throws SQLException {
        Set<String> wanted = new HashSet<>();
        nameKeys.forEach((key, nameKey) -> {
            wanted.add(key.toLowerCase(Locale.ROOT));
            if (nameKey.typed != null) wanted.add(nameKey.typed.toLowerCase(Locale.ROOT));
        });
        for (Row ban : addressBans) {
            if (ban.name != null) wanted.add(ban.name.toLowerCase(Locale.ROOT));
        }
        Map<String, String> accounts = new HashMap<>();
        if (wanted.isEmpty()) return accounts;
        session.query("SELECT " + column("uuid") + ", " + column("name") + " FROM " + table(HISTORY), row -> {
            String uuid = PlayerKeys.uuidOrNull(row.getString(1));
            String name = row.getString(2);
            if (uuid == null || !SourceSupport.isPlayerName(name)) return;
            String lower = name.toLowerCase(Locale.ROOT);
            if (wanted.contains(lower)) accounts.put(lower, uuid);
        });
        return accounts;
    }

    private void addressBan(Keys found, Row ban, Map<String, String> accountsByName) {
        String name = ban.name;
        String account = name != null && !PlayerKeys.isAddressLiteral(name) ? accountOfName(name, name, accountsByName) : null;
        if (account == null) {
            found.counts.withoutAccount();
            return;
        }
        found.counts.scoped(AdvancedBanType.IP_BAN.getScope());
        found.accounts.add(account);
        found.addressBans.computeIfAbsent(account, unseen -> new ArrayList<>()).add(ban);
    }

    private @Nullable String accountOfName(String key, @Nullable String typed, Map<String, String> accountsByName) {
        if (!SourceSupport.isPlayerName(key)) return null;
        String known = accountsByName.get(key.toLowerCase(Locale.ROOT));
        if (known != null) return known;
        known = knownNames.uuidOf(key);
        if (known != null) return known;
        if (!context.getOfflineUuids().getAsBoolean()) return null;
        return PlayerKeys.offlineUuid(typed != null && SourceSupport.isPlayerName(typed) ? typed : key);
    }

    private Map<String, SourcePlayer> batch(Keys found, Map<String, List<Row>> addressBans, List<String> accounts)
            throws SQLException {
        Map<String, PlayerHistory> histories = new LinkedHashMap<>();
        Map<String, String> accountsByRawKey = new HashMap<>();
        for (String account : accounts) {
            histories.put(account, new PlayerHistory());
            accountsByRawKey.put(account, account);
            accountsByRawKey.put(account.replace("-", ""), account);
            for (String nameKey : found.extraKeysByAccount.getOrDefault(account, Collections.emptySet())) {
                accountsByRawKey.put(nameKey, account);
            }
        }
        List<String> rawKeys = new ArrayList<>(accountsByRawKey.keySet());
        long now = context.now();
        for (int start = 0; start < rawKeys.size(); start += BatchedPlayers.BATCH_SIZE) {
            List<String> chunk = rawKeys.subList(start, Math.min(rawKeys.size(), start + BatchedPlayers.BATCH_SIZE));
            session.query(rowsQuery() + " WHERE " + column("uuid") + " IN (" + SqlSession.placeholders(chunk.size()) + ")",
                    SqlSession.bindAll(chunk), row -> {
                Row punished = row(row);
                String account = punished.key == null ? null : accountsByRawKey.get(punished.key);
                PlayerHistory history = account == null ? null : histories.get(account);
                if (history != null) record(found, history, punished, now);
            });
        }
        Map<String, SourcePlayer> players = new HashMap<>();
        histories.forEach((account, history) -> players.put(account, finish(found, addressBans, account, history, now)));
        return players;
    }

    private void record(Keys found, PlayerHistory history, Row punished, long now) {
        AdvancedBanType type = punished.type;
        if (type == null || type.isAddressWide() || isDeletedNote(found, type, punished.key, punished.start)) return;
        if (SourceSupport.isPlayerName(punished.name)) history.seen(punished.name, null, punished.start);
        history.punished(punishment(punished, type, found.active, now));
    }

    private SourcePlayer finish(Keys found, Map<String, List<Row>> addressBans, String account, PlayerHistory history,
                                long now) {
        List<Row> bans = addressBans.remove(account);
        if (bans != null) {
            for (Row ban : bans) history.punished(punishment(ban, AdvancedBanType.IP_BAN, found.active, now));
        }
        return history.toPlayer(account);
    }

    private String rowsQuery() {
        return "SELECT " + column("id") + ", " + column("name") + ", " + column("uuid") + ", " + column("reason") + ", "
                + column("operator") + ", " + column("punishmentType") + ", " + column("start") + ", " + column("end")
                + " FROM " + table(HISTORY);
    }

    private static Row row(ResultSet row) throws SQLException {
        return Row.builder()
                .id(row.getLong(1))
                .name(row.getString(2))
                .key(row.getString(3))
                .reason(row.getString(4))
                .operator(row.getString(5))
                .type(AdvancedBanType.of(row.getString(6)))
                .start(row.getLong(7))
                .end(row.getLong(8))
                .build();
    }

    private SourcePunishment punishment(Row row, AdvancedBanType scopeType, Set<String> active, long now) {
        AdvancedBanType type = row.type != null ? row.type : scopeType;
        PunishmentKind kind = type.getKind();
        boolean permanent = row.end == PERMANENT || row.end == 0;
        boolean lapsed = !permanent && row.end <= now;
        boolean stillActive = active.contains(activeKey(row.key, row.start, type));
        boolean enforced = kind.isTimed() || kind == PunishmentKind.WARNING;
        return SourcePunishment.builder()
                .id("advancedban-" + row.id)
                .kind(kind)
                .scope(type.getScope())
                .reason(SourceSupport.textOr(row.reason, context.getDefaultReason().get()))
                .issuerName(SourceSupport.issuer(row.operator))
                .issuedAt(row.start > 0 ? row.start : now)
                .duration(kind.isTimed() && !permanent ? SourcePunishment.durationBetween(row.start, row.end) : 0)
                .inForce(enforced && stillActive && !lapsed)
                .endedEarly(enforced && !stillActive && !lapsed)
                .build();
    }

    private static String activeKey(@Nullable String key, long start, @Nullable AdvancedBanType type) {
        return key + "|" + start + "|" + (type == null ? "" : type.getKind() + type.getScope().name());
    }

    private String table(String name) {
        return session.dialect() == SqlDialect.HSQLDB ? name.toUpperCase(Locale.ROOT) : session.quote(name);
    }

    private String column(String name) {
        return session.dialect() == SqlDialect.HSQLDB ? session.quote(name.toUpperCase(Locale.ROOT)) : session.quote(name);
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

    private final class Players implements PlayerRecords {
        private final Keys found;
        private final Map<String, List<Row>> addressBans;
        private final long now = context.now();
        private final PlayerRecords byName;
        private SqlSession.@Nullable Cursor cursor;
        private boolean streamed;
        private @Nullable Row pending;
        private @Nullable Iterator<Map.Entry<String, List<Row>>> addressOnly;

        private Players(Keys found) {
            this.found = found;
            this.addressBans = new LinkedHashMap<>(found.addressBans);
            this.byName = new BatchedPlayers(BatchedPlayers.Keys.of(new TreeMap<>(found.extraKeysByAccount).keySet()),
                    accounts -> batch(found, addressBans, accounts),
                    () -> session.query("SELECT 1 FROM " + table(ACTIVE) + " WHERE 1 = 0", row -> { }));
        }

        @Override
        public @Nullable SourcePlayer next() throws SourceException, UnreadablePlayerException {
            if (!streamed) {
                SourcePlayer streamedPlayer = nextStreamed();
                if (streamedPlayer != null) return streamedPlayer;
            }
            SourcePlayer named = byName.next();
            if (named != null) return named;
            return nextAddressOnly();
        }

        private @Nullable SourcePlayer nextStreamed() throws SourceException {
            try {
                SqlSession.Cursor rows = open();
                String account = null;
                PlayerHistory history = new PlayerHistory();
                while (true) {
                    Row row = pending;
                    pending = null;
                    if (row == null && rows.next()) row = row(rows.row());
                    if (row == null) {
                        endStream();
                        return account == null ? null : finish(found, addressBans, account, history, now);
                    }
                    String uuid = PlayerKeys.uuidOrNull(row.key);
                    if (uuid == null || found.extraKeysByAccount.containsKey(uuid)) continue;
                    if (account != null && !account.equals(uuid)) {
                        pending = row;
                        return finish(found, addressBans, account, history, now);
                    }
                    account = uuid;
                    record(found, history, row, now);
                }
            } catch (SQLException | RuntimeException failure) {
                endStream();
                throw SourceException.failed(failure);
            }
        }

        private SqlSession.Cursor open() throws SQLException {
            SqlSession.Cursor rows = cursor;
            if (rows != null) return rows;
            rows = session.cursor(rowsQuery() + " WHERE " + column("punishmentType") + " NOT IN " + IP_TYPES
                    + " ORDER BY " + column("uuid") + ", " + column("id"));
            cursor = rows;
            return rows;
        }

        private void endStream() {
            streamed = true;
            SqlSession.Cursor rows = cursor;
            cursor = null;
            if (rows == null) return;
            try {
                rows.close();
            } catch (SQLException ignored) {
                context.getLogger().debug("The AdvancedBan history cursor could not be closed");
            }
        }

        private @Nullable SourcePlayer nextAddressOnly() {
            Iterator<Map.Entry<String, List<Row>>> remaining = addressOnly;
            if (remaining == null) {
                remaining = new ArrayList<>(addressBans.entrySet()).iterator();
                addressOnly = remaining;
            }
            if (!remaining.hasNext()) return null;
            String account = remaining.next().getKey();
            return finish(found, addressBans, account, new PlayerHistory(), now);
        }
    }

    @Value
    @Builder
    private static class Row {
        long id;
        @Nullable String name;
        @Nullable String key;
        @Nullable String reason;
        @Nullable String operator;
        @Nullable AdvancedBanType type;
        long start;
        long end;
    }

    private static final class NameKey {
        private @Nullable String typed;
        private long rows;

        private void seen(@Nullable String name) {
            if (typed == null) typed = name;
            rows++;
        }
    }

    private static final class Keys {
        private final PlayerKeySet accounts = new PlayerKeySet();
        private final Map<String, Set<String>> extraKeysByAccount = new HashMap<>();
        private final Map<String, List<Row>> addressBans = new LinkedHashMap<>();
        private final Set<String> active = new HashSet<>();
        private final InventoryCounter counts = new InventoryCounter();
        private int players;
    }
}
