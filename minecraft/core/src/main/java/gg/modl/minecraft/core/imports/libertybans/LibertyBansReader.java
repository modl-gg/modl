package gg.modl.minecraft.core.imports.libertybans;

import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.PunishmentScope;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.BatchedPlayers;
import gg.modl.minecraft.core.imports.sql.SqlSession;
import gg.modl.minecraft.core.imports.support.AddressPunishment;
import gg.modl.minecraft.core.imports.support.InventoryCounter;
import gg.modl.minecraft.core.imports.support.LatestValues;
import gg.modl.minecraft.core.imports.support.PlayerHistory;
import gg.modl.minecraft.core.imports.support.PlayerKeySet;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class LibertyBansReader implements SourceReader {
    private static final String CONSOLE = "00000000-0000-0000-0000-000000000000";
    private static final String PREFIX = "libertybans_";
    private static final int ADDRESS = 1, COMPOSITE = 2;
    private static final int BAN = 0, MUTE = 1, WARN = 2, KICK = 3;
    private static final long SECOND = TimeUnit.SECONDS.toMillis(1);

    private final SqlSession session;
    private final SourceContext context;
    private @Nullable Loaded loaded;

    LibertyBansReader(SqlSession session, SourceContext context) {
        this.session = session;
        this.context = context;
    }

    @Override
    public SourceInventory inventory() throws SourceException {
        Loaded found = load();
        return found.counts.toInventory(found.players);
    }

    @Override
    public long historyEntriesBefore(long epochMillis) throws SourceException {
        try {
            return session.count("SELECT COUNT(*) FROM " + table("addresses") + " WHERE " + q("updated") + " < ?",
                    statement -> statement.setLong(1, epochMillis / SECOND));
        } catch (SQLException failure) {
            throw SourceException.failed(failure);
        }
    }

    @Override
    public PlayerRecords players() throws SourceException {
        Loaded found = load();
        Map<String, List<SourcePunishment>> byAddress = new HashMap<>(found.byAddress);
        return new BatchedPlayers(BatchedPlayers.Keys.of(found.uuids), uuids -> batch(found, byAddress, uuids),
                () -> session.query("SELECT 1 FROM " + table("names") + " WHERE 1 = 0", row -> { }));
    }

    private Loaded load() throws SourceException {
        Loaded found = loaded;
        if (found != null) return found;
        try {
            PlayerLimit.require(session.count("SELECT COUNT(DISTINCT " + q("uuid") + ") FROM " + table("names")));
            found = new Loaded(session.hasColumn(PREFIX + "punishments", "scope_id"));
            readActive(found);
            readOperators(found);
            readPlayers(found);
            readCounts(found);
            readAddressPunishments(found);
            found.players = found.uuids.size();
        } catch (SQLException failure) {
            throw SourceException.failed(failure);
        }
        loaded = found;
        return found;
    }

    private void readActive(Loaded found) throws SQLException {
        for (String kind : new String[]{"bans", "mutes", "warns"}) {
            session.query("SELECT " + q("id") + " FROM " + table(kind), row -> found.active.add(row.getLong(1)));
        }
    }

    private void readOperators(Loaded found) throws SQLException {
        Set<String> operators = new LinkedHashSet<>();
        session.query("SELECT DISTINCT " + q("operator") + " FROM " + table("punishments"), row -> {
            String uuid = uuidOf(row, 1);
            if (uuid != null) operators.add(uuid);
        });
        operators.remove(CONSOLE);
        found.operators.put(CONSOLE, Constants.DEFAULT_CONSOLE_NAME);
        LatestValues names = new LatestValues();
        for (List<String> chunk : chunks(new ArrayList<>(operators))) {
            session.query("SELECT " + q("uuid") + ", " + q("name") + ", " + q("updated") + " FROM " + table("names")
                    + " WHERE " + q("uuid") + " IN (" + SqlSession.placeholders(chunk.size()) + ")",
                    statement -> bindUuids(statement, chunk), row -> names.seen(uuidOf(row, 1), row.getString(2), row.getLong(3)));
        }
        for (String operator : operators) {
            String name = names.latest(operator);
            if (name != null) found.operators.put(operator, name);
        }
    }

    private void readPlayers(Loaded found) throws SQLException {
        for (String source : new String[]{"names", "addresses"}) {
            session.query("SELECT DISTINCT " + q("uuid") + " FROM " + table(source), row -> addPlayer(found, uuidOf(row, 1)));
        }
        session.query("SELECT " + q("uuid") + " FROM " + table("victims") + " WHERE " + q("type") + " <> " + ADDRESS,
                row -> addPlayer(found, uuidOf(row, 1)));
    }

    private static void addPlayer(Loaded found, @Nullable String uuid) {
        if (uuid != null && !CONSOLE.equals(uuid)) found.uuids.add(uuid);
    }

    private void readCounts(Loaded found) throws SQLException {
        session.query("SELECT p." + q("type") + ", v." + q("type") + ", " + scopeColumn(found) + " FROM "
                + table("history") + " h JOIN " + table("punishments") + " p ON p." + q("id") + " = h." + q("id")
                + " JOIN " + table("victims") + " v ON v." + q("id") + " = h." + q("victim"), row -> {
            PunishmentKind kind = kindOf(row.getInt(1));
            if (kind == null) return;
            found.counts.counted(kind);
            found.counts.scoped(scopeOf(row.getInt(2), isScoped(row, 3)));
        });
    }

    private void readAddressPunishments(Loaded found) throws SQLException {
        List<AddressPunishment> addressed = new ArrayList<>();
        session.query(punishmentQuery(found) + " WHERE v." + q("type") + " = " + ADDRESS, row -> {
            byte[] address = row.getBytes(4);
            SourcePunishment punishment = punishment(found, row);
            if (punishment != null) addressed.add(new AddressPunishment(PlayerKeys.addressOrNull(address), punishment));
        });
        SoleOwners owners = soleOwners(addressed);
        for (AddressPunishment entry : addressed) {
            String owner = owners.soleOwner(entry.getAddress());
            if (owner == null) {
                found.counts.withoutAccount();
                continue;
            }
            found.byAddress.computeIfAbsent(owner, unseen -> new ArrayList<>())
                    .add(entry.getPunishment().attributedToTheOnlyAccountOfItsAddress());
        }
    }

    private SoleOwners soleOwners(List<AddressPunishment> addressed) throws SQLException {
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
            session.query("SELECT " + q("uuid") + ", " + q("address") + " FROM " + table("addresses")
                    + " WHERE " + q("address") + " IN (" + SqlSession.placeholders(raw.size()) + ")", SqlSession.bindAll(raw),
                    row -> owners.used(PlayerKeys.addressOrNull(row.getBytes(2)), playerOrNull(uuidOf(row, 1))));
        }
        return owners;
    }

    private static @Nullable String playerOrNull(@Nullable String uuid) {
        return CONSOLE.equals(uuid) ? null : uuid;
    }

    private static List<List<String>> chunks(List<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += BatchedPlayers.BATCH_SIZE) {
            chunks.add(values.subList(start, Math.min(values.size(), start + BatchedPlayers.BATCH_SIZE)));
        }
        return chunks;
    }

    private Map<String, SourcePlayer> batch(Loaded found, Map<String, List<SourcePunishment>> byAddress, List<String> uuids)
            throws SQLException {
        Map<String, PlayerHistory> histories = new LinkedHashMap<>();
        for (String uuid : uuids) histories.put(uuid, new PlayerHistory());
        String marks = SqlSession.placeholders(uuids.size());
        session.query("SELECT " + q("uuid") + ", " + q("name") + ", " + q("updated") + " FROM " + table("names")
                + " WHERE " + q("uuid") + " IN (" + marks + ")", statement -> bindUuids(statement, uuids), row -> {
            PlayerHistory history = histories.get(uuidOf(row, 1));
            if (history != null) history.seen(row.getString(2), null, row.getLong(3) * SECOND);
        });
        session.query("SELECT " + q("uuid") + ", " + q("address") + ", " + q("updated") + " FROM " + table("addresses")
                + " WHERE " + q("uuid") + " IN (" + marks + ")", statement -> bindUuids(statement, uuids), row -> {
            PlayerHistory history = histories.get(uuidOf(row, 1));
            if (history != null) history.seen(null, PlayerKeys.addressOrNull(row.getBytes(2)), row.getLong(3) * SECOND);
        });
        session.query(punishmentQuery(found) + " WHERE v." + q("type") + " <> " + ADDRESS + " AND v." + q("uuid")
                + " IN (" + marks + ")", statement -> bindUuids(statement, uuids), row -> {
            PlayerHistory history = histories.get(uuidOf(row, 5));
            SourcePunishment punishment = punishment(found, row);
            if (history != null && punishment != null) history.punished(punishment);
        });
        Map<String, SourcePlayer> players = new HashMap<>();
        for (Map.Entry<String, PlayerHistory> entry : histories.entrySet()) {
            List<SourcePunishment> extra = byAddress.remove(entry.getKey());
            if (extra != null) extra.forEach(entry.getValue()::punished);
            players.put(entry.getKey(), entry.getValue().toPlayer(entry.getKey()));
        }
        return players;
    }

    private String punishmentQuery(Loaded found) {
        return "SELECT p." + q("id") + ", p." + q("type") + ", v." + q("type") + ", v." + q("address") + ", v."
                + q("uuid") + ", p." + q("operator") + ", p." + q("reason") + ", p." + q("start") + ", p." + q("end")
                + ", " + scopeColumn(found) + " FROM " + table("history") + " h JOIN " + table("punishments")
                + " p ON p." + q("id") + " = h." + q("id") + " JOIN " + table("victims") + " v ON v." + q("id")
                + " = h." + q("victim");
    }

    private @Nullable SourcePunishment punishment(Loaded found, ResultSet row) throws SQLException {
        PunishmentKind kind = kindOf(row.getInt(2));
        if (kind == null) return null;
        long id = row.getLong(1);
        long now = context.now();
        long start = row.getLong(8) * SECOND;
        long end = row.getLong(9) * SECOND;
        boolean permanent = end <= 0;
        boolean lapsed = !permanent && end <= now;
        boolean active = found.active.contains(id);
        boolean enforced = kind != PunishmentKind.KICK;
        String operator = uuidOf(row, 6);
        String issuer = operator == null ? null : found.operators.get(operator);
        return SourcePunishment.builder()
                .id("libertybans-" + id)
                .kind(kind)
                .scope(scopeOf(row.getInt(3), isScoped(row, 10)))
                .reason(SourceSupport.textOr(row.getString(7), context.getDefaultReason().get()))
                .issuerName(issuer)
                .issuedAt(start > 0 ? start : now)
                .duration(kind.isTimed() && !permanent ? SourcePunishment.durationBetween(start, end) : 0)
                .inForce(enforced && active && !lapsed)
                .endedEarly(enforced && !active && !lapsed)
                .build();
    }

    private static @Nullable PunishmentKind kindOf(int type) {
        switch (type) {
            case BAN:
                return PunishmentKind.BAN;
            case MUTE:
                return PunishmentKind.MUTE;
            case WARN:
                return PunishmentKind.WARNING;
            case KICK:
                return PunishmentKind.KICK;
            default:
                return null;
        }
    }

    private static PunishmentScope scopeOf(int victimType, boolean scoped) {
        if (victimType == ADDRESS || victimType == COMPOSITE) return PunishmentScope.IP_WIDE;
        return scoped ? PunishmentScope.SERVER_SCOPED : PunishmentScope.ACCOUNT;
    }

    private static boolean isScoped(ResultSet row, int column) throws SQLException {
        Object scope = row.getObject(column);
        if (scope == null) return false;
        if (scope instanceof Number) return true;
        return !String.valueOf(scope).trim().isEmpty();
    }

    private String scopeColumn(Loaded found) {
        return "p." + (found.scopeIds ? q("scope_id") : q("scope"));
    }

    private void bindUuids(PreparedStatement statement, List<String> uuids) throws SQLException {
        for (int index = 0; index < uuids.size(); index++) bindUuid(statement, index + 1, uuids.get(index));
    }

    private void bindUuid(PreparedStatement statement, int index, String uuid) throws SQLException {
        if (session.dialect().storesUuidNatively()) statement.setObject(index, UUID.fromString(uuid));
        else statement.setBytes(index, PlayerKeys.bytesOf(uuid));
    }

    private static @Nullable String uuidOf(ResultSet row, int column) throws SQLException {
        Object value = row.getObject(column);
        if (value instanceof UUID) return value.toString();
        if (value instanceof byte[]) return PlayerKeys.uuidOrNull((byte[]) value);
        return value == null ? null : PlayerKeys.uuidOrNull(String.valueOf(value));
    }

    private String table(String name) {
        return session.quote(PREFIX + name);
    }

    private String q(String column) {
        return session.quote(column);
    }

    @Override
    public void cancelRunning() {
        session.cancelRunning();
    }

    @Override
    public void close() {
        session.close();
    }

    private static final class Loaded {
        private final boolean scopeIds;
        private final Set<Long> active = new HashSet<>();
        private final Map<String, String> operators = new HashMap<>();
        private final PlayerKeySet uuids = new PlayerKeySet();
        private final Map<String, List<SourcePunishment>> byAddress = new HashMap<>();
        private final InventoryCounter counts = new InventoryCounter();
        private int players;

        private Loaded(boolean scopeIds) {
            this.scopeIds = scopeIds;
        }
    }
}
