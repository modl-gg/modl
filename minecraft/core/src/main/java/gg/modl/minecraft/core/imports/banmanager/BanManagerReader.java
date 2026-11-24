package gg.modl.minecraft.core.imports.banmanager;

import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.PunishmentScope;
import gg.modl.minecraft.core.imports.source.Removal;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.BatchedPlayers;
import gg.modl.minecraft.core.imports.sql.SqlDialect;
import gg.modl.minecraft.core.imports.sql.SqlSession;
import gg.modl.minecraft.core.imports.support.AddressPunishment;
import gg.modl.minecraft.core.imports.support.InventoryCounter;
import gg.modl.minecraft.core.imports.support.PlayerHistory;
import gg.modl.minecraft.core.imports.support.PlayerKeys;
import gg.modl.minecraft.core.imports.support.PlayerLimit;
import gg.modl.minecraft.core.imports.support.SoleOwners;
import gg.modl.minecraft.core.imports.support.SourceSupport;
import gg.modl.minecraft.core.util.Constants;
import org.jspecify.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class BanManagerReader implements SourceReader {
    private static final long SECOND = TimeUnit.SECONDS.toMillis(1);
    private static final String ACTIVE = "id, %s, reason, actor_id, created, expires";
    private static final String RECORD = "id, %s, reason, expired, actor_id, pastActor_id, pastCreated, created, createdReason";

    private final SqlSession session;
    private final SourceContext context;
    private final BanManagerTables tables;
    private final @Nullable String console;
    private @Nullable Loaded loaded;

    BanManagerReader(SqlSession session, SourceContext context, BanManagerTables tables, @Nullable String console) {
        this.session = session;
        this.context = context;
        this.tables = tables;
        this.console = console;
    }

    @Override
    public SourceInventory inventory() throws SourceException {
        Loaded found = load();
        return found.counts.toInventory(found.players);
    }

    @Override
    public long historyEntriesBefore(long epochMillis) throws SourceException {
        try {
            return session.count("SELECT COUNT(*) FROM " + tables.getHistory() + " WHERE " + column("join") + " < ?",
                    statement -> statement.setLong(1, epochMillis / SECOND));
        } catch (SQLException failure) {
            throw SourceException.failed(failure);
        }
    }

    @Override
    public PlayerRecords players() throws SourceException {
        Loaded found = load();
        Map<String, List<SourcePunishment>> byAddress = new HashMap<>(found.byAddress);
        return new BatchedPlayers(new PlayerPages(), uuids -> batch(found, byAddress, uuids),
                () -> session.query("SELECT 1 FROM " + tables.getPlayers() + " WHERE 1 = 0", row -> { }));
    }

    private Loaded load() throws SourceException {
        Loaded found = loaded;
        if (found != null) return found;
        try {
            found = new Loaded(presentTables(), session.hasColumn(tables.getHistory(), "name"),
                    session.hasColumn(tables.getMutes(), "pausedRemaining"));
            found.players = countPlayers();
            PlayerLimit.require(found.players);
            countPlayerTables(found);
            readActors(found);
            readAddressPunishments(found);
            countUnmapped(found);
        } catch (SQLException failure) {
            throw SourceException.failed(failure);
        }
        loaded = found;
        return found;
    }

    private Set<String> presentTables() throws SQLException {
        Set<String> present = new HashSet<>();
        for (String table : tables.all()) {
            if (session.hasTable(table)) present.add(table);
        }
        return present;
    }

    private int countPlayers() throws SQLException {
        long players = console == null ? session.count("SELECT COUNT(*) FROM " + tables.getPlayers())
                : session.count("SELECT COUNT(*) FROM " + tables.getPlayers() + " WHERE id <> ?",
                statement -> statement.setBytes(1, PlayerKeys.bytesOf(console)));
        return (int) Math.min(Integer.MAX_VALUE, players);
    }

    private void countPlayerTables(Loaded found) throws SQLException {
        count(found, tables.getBans(), PunishmentKind.BAN);
        count(found, tables.getBanRecords(), PunishmentKind.BAN);
        count(found, tables.getMutes(), PunishmentKind.MUTE);
        count(found, tables.getMuteRecords(), PunishmentKind.MUTE);
        count(found, tables.getWarnings(), PunishmentKind.WARNING);
        count(found, tables.getKicks(), PunishmentKind.KICK);
        count(found, tables.getNotes(), PunishmentKind.NOTE);
    }

    private void count(Loaded found, String table, PunishmentKind kind) throws SQLException {
        if (!found.has(table)) return;
        found.counts.counted(kind, session.count("SELECT COUNT(*) FROM " + table));
    }

    private void readActors(Loaded found) throws SQLException {
        Set<String> actors = new LinkedHashSet<>();
        for (String table : new String[]{tables.getBans(), tables.getBanRecords(), tables.getMutes(), tables.getMuteRecords(),
                tables.getWarnings(), tables.getKicks(), tables.getNotes(), tables.getIpBans(), tables.getIpBanRecords(),
                tables.getIpMutes(), tables.getIpMuteRecords()}) {
            if (!found.has(table)) continue;
            collectActors(actors, "SELECT DISTINCT actor_id FROM " + table);
            if (table.equals(tables.getBanRecords()) || table.equals(tables.getMuteRecords())
                    || table.equals(tables.getIpBanRecords()) || table.equals(tables.getIpMuteRecords())) {
                collectActors(actors, "SELECT DISTINCT pastActor_id FROM " + table);
            }
        }
        for (List<String> chunk : chunks(new ArrayList<>(actors))) {
            session.query("SELECT id, name FROM " + tables.getPlayers() + " WHERE id IN ("
                    + SqlSession.placeholders(chunk.size()) + ")", statement -> bindUuids(statement, chunk), row -> {
                String uuid = PlayerKeys.uuidOrNull(row.getBytes(1));
                if (uuid != null) found.actors.put(uuid, row.getString(2));
            });
        }
    }

    private void collectActors(Set<String> actors, String sql) throws SQLException {
        session.query(sql, row -> {
            String uuid = PlayerKeys.uuidOrNull(row.getBytes(1));
            if (uuid != null) actors.add(uuid);
        });
    }

    private void readAddressPunishments(Loaded found) throws SQLException {
        List<AddressPunishment> addressed = new ArrayList<>();
        readAddressed(found, addressed, tables.getIpBans(), PunishmentKind.BAN, false);
        readAddressed(found, addressed, tables.getIpBanRecords(), PunishmentKind.BAN, true);
        readAddressed(found, addressed, tables.getIpMutes(), PunishmentKind.MUTE, false);
        readAddressed(found, addressed, tables.getIpMuteRecords(), PunishmentKind.MUTE, true);
        SoleOwners owners = soleOwners(found, addressed);
        for (AddressPunishment entry : addressed) {
            SourcePunishment punishment = entry.getPunishment();
            found.counts.counted(punishment.getKind());
            found.counts.scoped(PunishmentScope.IP_WIDE);
            String owner = owners.soleOwner(entry.getAddress());
            if (owner == null) {
                found.counts.withoutAccount();
                continue;
            }
            found.byAddress.computeIfAbsent(owner, unseen -> new ArrayList<>())
                    .add(punishment.attributedToTheOnlyAccountOfItsAddress());
        }
    }

    private void readAddressed(Loaded found, List<AddressPunishment> addressed, String table, PunishmentKind kind,
                               boolean records) throws SQLException {
        if (!found.has(table)) return;
        String prefix = "banmanager-ip" + kind.name().toLowerCase(Locale.ROOT) + (records ? "record-" : "-");
        session.query("SELECT " + String.format(records ? RECORD : ACTIVE, "ip") + " FROM " + table, row -> {
            SourcePunishment punishment = records ? record(found, row, kind, prefix) : active(found, row, kind, prefix, false);
            addressed.add(new AddressPunishment(PlayerKeys.addressOrNull(row.getBytes(2)), punishment));
        });
    }

    private SoleOwners soleOwners(Loaded found, List<AddressPunishment> addressed) throws SQLException {
        Set<String> addresses = new LinkedHashSet<>();
        for (AddressPunishment entry : addressed) {
            if (entry.getAddress() != null) addresses.add(entry.getAddress());
        }
        SoleOwners owners = new SoleOwners();
        for (List<String> chunk : chunks(new ArrayList<>(addresses))) {
            List<byte[]> raw = new ArrayList<>();
            for (String address : chunk) {
                byte[] bytes = PlayerKeys.bytesOfAddress(address);
                if (bytes != null) raw.add(bytes);
            }
            if (raw.isEmpty()) continue;
            String in = " IN (" + SqlSession.placeholders(raw.size()) + ")";
            session.query("SELECT id, ip FROM " + tables.getPlayers() + " WHERE ip" + in, SqlSession.bindAll(raw),
                    row -> owners.used(PlayerKeys.addressOrNull(row.getBytes(2)), account(row.getBytes(1))));
            if (!found.has(tables.getHistory())) continue;
            session.query("SELECT player_id, ip FROM " + tables.getHistory() + " WHERE ip" + in, SqlSession.bindAll(raw),
                    row -> owners.used(PlayerKeys.addressOrNull(row.getBytes(2)), account(row.getBytes(1))));
        }
        return owners;
    }

    private @Nullable String account(byte @Nullable [] id) {
        String uuid = PlayerKeys.uuidOrNull(id);
        return uuid == null || uuid.equals(console) ? null : uuid;
    }

    private void countUnmapped(Loaded found) throws SQLException {
        for (String table : new String[]{tables.getIpRangeBans(), tables.getIpRangeBanRecords(), tables.getNameBans(),
                tables.getNameBanRecords()}) {
            if (!found.has(table)) continue;
            long rows = session.count("SELECT COUNT(*) FROM " + table);
            found.counts.counted(PunishmentKind.BAN, rows);
            found.counts.withoutAccount(rows);
        }
    }

    private static <T> List<List<T>> chunks(List<T> values) {
        List<List<T>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += BatchedPlayers.BATCH_SIZE) {
            chunks.add(values.subList(start, Math.min(values.size(), start + BatchedPlayers.BATCH_SIZE)));
        }
        return chunks;
    }

    private Map<String, SourcePlayer> batch(Loaded found, Map<String, List<SourcePunishment>> byAddress, List<String> uuids)
            throws SQLException {
        Map<String, PlayerHistory> histories = new LinkedHashMap<>();
        for (String uuid : uuids) histories.put(uuid, new PlayerHistory());
        String in = " WHERE player_id IN (" + SqlSession.placeholders(uuids.size()) + ")";
        SqlSession.Binder binder = statement -> bindUuids(statement, uuids);
        session.query("SELECT id, name, ip, lastSeen FROM " + tables.getPlayers() + " WHERE id IN ("
                + SqlSession.placeholders(uuids.size()) + ")", binder, row -> {
            PlayerHistory history = histories.get(PlayerKeys.uuidOrNull(row.getBytes(1)));
            if (history != null) history.seen(row.getString(2), PlayerKeys.addressOrNull(row.getBytes(3)), row.getLong(4) * SECOND);
        });
        if (found.has(tables.getHistory())) {
            session.query("SELECT player_id, " + (found.historyNames ? "name" : "NULL") + ", ip, " + column("join") + " FROM "
                    + tables.getHistory() + in, binder, row -> {
                PlayerHistory history = histories.get(PlayerKeys.uuidOrNull(row.getBytes(1)));
                if (history != null) history.seen(row.getString(2), PlayerKeys.addressOrNull(row.getBytes(3)), row.getLong(4) * SECOND);
            });
        }
        punishments(found, histories, in, binder, tables.getBans(), String.format(ACTIVE, "player_id"),
                row -> active(found, row, PunishmentKind.BAN, "banmanager-ban-", false));
        punishments(found, histories, in, binder, tables.getBanRecords(), String.format(RECORD, "player_id"),
                row -> record(found, row, PunishmentKind.BAN, "banmanager-banrecord-"));
        punishments(found, histories, in, binder, tables.getMutes(), String.format(ACTIVE, "player_id")
                        + (found.pausedMutes ? ", onlineOnly, " + column("pausedRemaining") : ""),
                row -> active(found, row, PunishmentKind.MUTE, "banmanager-mute-", found.pausedMutes));
        punishments(found, histories, in, binder, tables.getMuteRecords(), String.format(RECORD, "player_id"),
                row -> record(found, row, PunishmentKind.MUTE, "banmanager-muterecord-"));
        punishments(found, histories, in, binder, tables.getWarnings(), "id, player_id, reason, actor_id, created",
                row -> simple(found, row, PunishmentKind.WARNING, "banmanager-warning-"));
        punishments(found, histories, in, binder, tables.getKicks(), "id, player_id, reason, actor_id, created",
                row -> simple(found, row, PunishmentKind.KICK, "banmanager-kick-"));
        punishments(found, histories, in, binder, tables.getNotes(), "id, player_id, message, actor_id, created",
                row -> simple(found, row, PunishmentKind.NOTE, "banmanager-note-"));
        Map<String, SourcePlayer> players = new HashMap<>();
        for (Map.Entry<String, PlayerHistory> entry : histories.entrySet()) {
            List<SourcePunishment> extra = byAddress.remove(entry.getKey());
            if (extra != null) extra.forEach(entry.getValue()::punished);
            players.put(entry.getKey(), entry.getValue().toPlayer(entry.getKey()));
        }
        return players;
    }

    private void punishments(Loaded found, Map<String, PlayerHistory> histories, String in, SqlSession.Binder binder, String table,
                             String columns, RowMapper mapper) throws SQLException {
        if (!found.has(table)) return;
        session.query("SELECT " + columns + " FROM " + table + in, binder, row -> {
            PlayerHistory history = histories.get(PlayerKeys.uuidOrNull(row.getBytes(2)));
            if (history != null) history.punished(mapper.map(row));
        });
    }

    private SourcePunishment active(Loaded found, ResultSet row, PunishmentKind kind, String prefix, boolean paused)
            throws SQLException {
        long now = context.now();
        long created = row.getLong(5) * SECOND;
        long expires = row.getLong(6) * SECOND;
        long duration = expires > 0 ? SourcePunishment.durationBetween(created, expires) : 0;
        if (paused && expires == 0 && row.getBoolean(7) && row.getLong(8) > 0) {
            duration = Math.max(0, now - created) + row.getLong(8) * SECOND;
        }
        boolean lapsed = expires > 0 && expires <= now;
        return base(found, row, kind, prefix, created)
                .duration(duration)
                .inForce(!lapsed)
                .build();
    }

    private SourcePunishment record(Loaded found, ResultSet row, PunishmentKind kind, String prefix) throws SQLException {
        long issued = row.getLong(7) * SECOND;
        long expired = row.getLong(4) * SECOND;
        long ended = row.getLong(8) * SECOND;
        boolean ranOut = expired > 0 && ended >= expired;
        String remover = SourceSupport.textOr(actorName(found, PlayerKeys.uuidOrNull(row.getBytes(5))), Constants.UNKNOWN);
        String reason = row.getString(9);
        return SourcePunishment.builder()
                .id(prefix + row.getLong(1))
                .kind(kind)
                .reason(SourceSupport.textOr(row.getString(3), context.getDefaultReason().get()))
                .issuerName(actorName(found, PlayerKeys.uuidOrNull(row.getBytes(6))))
                .issuedAt(issued > 0 ? issued : ended)
                .duration(expired > 0 ? SourcePunishment.durationBetween(issued, expired) : 0)
                .removal(ranOut ? null : new Removal(remover, ended > 0 ? ended : null, SourceSupport.trimmedOrNull(reason)))
                .build();
    }

    private SourcePunishment simple(Loaded found, ResultSet row, PunishmentKind kind, String prefix) throws SQLException {
        return base(found, row, kind, prefix, row.getLong(5) * SECOND).build();
    }

    private SourcePunishment.SourcePunishmentBuilder base(Loaded found, ResultSet row, PunishmentKind kind, String prefix,
                                                          long created) throws SQLException {
        return SourcePunishment.builder()
                .id(prefix + row.getLong(1))
                .kind(kind)
                .reason(SourceSupport.textOr(row.getString(3), context.getDefaultReason().get()))
                .issuerName(actorName(found, PlayerKeys.uuidOrNull(row.getBytes(4))))
                .issuedAt(created > 0 ? created : context.now());
    }

    private @Nullable String actorName(Loaded found, @Nullable String uuid) {
        if (uuid == null) return null;
        if (uuid.equals(console)) return Constants.DEFAULT_CONSOLE_NAME;
        return SourceSupport.trimmedOrNull(found.actors.get(uuid));
    }

    private void bindUuids(PreparedStatement statement, List<String> uuids) throws SQLException {
        for (int index = 0; index < uuids.size(); index++) statement.setBytes(index + 1, PlayerKeys.bytesOf(uuids.get(index)));
    }

    private String column(String name) {
        return session.quote(session.dialect() == SqlDialect.H2 ? name.toUpperCase(Locale.ROOT) : name);
    }

    @Override
    public void cancelRunning() {
        session.cancelRunning();
    }

    @Override
    public void close() {
        session.close();
    }

    @FunctionalInterface
    private interface RowMapper {
        SourcePunishment map(ResultSet row) throws SQLException;
    }

    private final class PlayerPages implements BatchedPlayers.Keys {
        private byte @Nullable [] after;
        private boolean done;

        @Override
        public List<String> next(int limit) throws SQLException {
            List<String> page = new ArrayList<>(limit);
            while (page.isEmpty() && !done) readPage(page, limit);
            return page;
        }

        private void readPage(List<String> page, int limit) throws SQLException {
            byte[] start = after;
            int[] rows = {0};
            session.query("SELECT id FROM " + tables.getPlayers() + (start == null ? "" : " WHERE id > ?")
                    + " ORDER BY id LIMIT " + limit, statement -> {
                if (start != null) statement.setBytes(1, start);
            }, row -> {
                byte[] id = row.getBytes(1);
                rows[0]++;
                after = id;
                String uuid = PlayerKeys.uuidOrNull(id);
                if (uuid != null && !uuid.equals(console)) page.add(uuid);
            });
            if (rows[0] < limit) done = true;
        }
    }

    private static final class Loaded {
        private final Set<String> present;
        private final boolean historyNames;
        private final boolean pausedMutes;
        private final Map<String, String> actors = new HashMap<>();
        private final Map<String, List<SourcePunishment>> byAddress = new HashMap<>();
        private final InventoryCounter counts = new InventoryCounter();
        private int players;

        private Loaded(Set<String> present, boolean historyNames, boolean pausedMutes) {
            this.present = present;
            this.historyNames = historyNames;
            this.pausedMutes = pausedMutes;
        }

        private boolean has(String table) {
            return present.contains(table);
        }
    }
}
