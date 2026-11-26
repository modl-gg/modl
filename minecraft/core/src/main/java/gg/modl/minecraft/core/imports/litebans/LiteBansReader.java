package gg.modl.minecraft.core.imports.litebans;

import gg.modl.minecraft.api.DatabaseProvider;
import gg.modl.minecraft.core.imports.source.ImportProblem;
import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.PunishmentScope;
import gg.modl.minecraft.core.imports.source.Removal;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.source.UnreadablePlayerException;
import gg.modl.minecraft.core.imports.sql.CancellableQueries;
import gg.modl.minecraft.core.imports.support.InventoryCounter;
import gg.modl.minecraft.core.imports.support.PlayerHistory;
import gg.modl.minecraft.core.imports.support.PlayerKeySet;
import gg.modl.minecraft.core.imports.support.PlayerKeys;
import gg.modl.minecraft.core.imports.support.PlayerLimit;
import gg.modl.minecraft.core.util.Constants;
import org.jspecify.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

final class LiteBansReader implements SourceReader {
    static final String HISTORY_PLAYERS = "SELECT DISTINCT UUID FROM {history}";
    static final String HISTORY_PLAYER_COUNT = "SELECT COUNT(DISTINCT UUID) FROM {history}";
    static final String OLD_HISTORY = "SELECT COUNT(*) FROM {history} WHERE DATE < ?";
    static final String PROBE = "SELECT UUID FROM {history} WHERE 1 = 0";
    static final String PLAYER_HISTORY = "SELECT NAME, IP, DATE FROM {history} WHERE UUID IN (%s) ORDER BY DATE ASC";

    private static final String PUNISHMENT_SUMMARY = "SELECT UUID, IPBAN, SERVER_SCOPE FROM ";
    private static final String KICK_SUMMARY = "SELECT UUID FROM ";
    private static final String PLAYER_PUNISHMENTS = "SELECT ID, REASON, BANNED_BY_NAME, TIME, UNTIL, ACTIVE, IPBAN, "
            + "SERVER_SCOPE, REMOVED_BY_UUID, REMOVED_BY_NAME, REMOVED_BY_DATE FROM ";
    private static final String PLAYER_KICKS = "SELECT ID, REASON, BANNED_BY_NAME, TIME FROM ";
    private static final String EXPIRED_MARKER = "#expired";
    private static final String EVERY_SERVER = "*";

    private final DatabaseProvider database;
    private final String defaultReason;
    private final LongSupplier clock;
    private final BooleanSupplier enabled;
    private final CancellableQueries queries = new CancellableQueries();
    private @Nullable Players players;

    LiteBansReader(DatabaseProvider database, String defaultReason, LongSupplier clock, BooleanSupplier enabled) {
        this.database = database;
        this.defaultReason = defaultReason;
        this.clock = clock;
        this.enabled = enabled;
    }

    static String summaryOf(LiteBansTable table) {
        return (table.isRemovable() ? PUNISHMENT_SUMMARY : KICK_SUMMARY) + table.getToken();
    }

    static String punishmentsOf(LiteBansTable table) {
        return (table.isRemovable() ? PLAYER_PUNISHMENTS : PLAYER_KICKS) + table.getToken()
                + " WHERE UUID IN (%s) ORDER BY TIME ASC";
    }

    @Override
    public SourceInventory inventory() throws SourceException {
        try {
            return known().inventory;
        } catch (SQLException failure) {
            throw SourceException.failed(failure);
        }
    }

    private Players known() throws SQLException, SourceException {
        Players known = players;
        if (known != null) return known;
        long[] historyPlayers = {0};
        query(HISTORY_PLAYER_COUNT, statement -> { }, row -> historyPlayers[0] = row.getLong(1));
        PlayerLimit.require(historyPlayers[0]);
        Players found = new Players();
        query(HISTORY_PLAYERS, statement -> { }, row -> found.seen(row.getString("UUID")));
        InventoryCounter counts = new InventoryCounter();
        for (LiteBansTable table : LiteBansTable.values()) count(table, found, counts);
        found.inventory = counts.toInventory(found.uuids.size());
        players = found;
        return found;
    }

    private void count(LiteBansTable table, Players found, InventoryCounter counts) throws SQLException {
        query(summaryOf(table), statement -> { }, row -> {
            counts.counted(table.getKind());
            if (found.seen(row.getString("UUID")) == null) {
                counts.withoutAccount();
                return;
            }
            if (table.isRemovable()) counts.scoped(scopeOf(row));
        });
    }

    @Override
    public long historyEntriesBefore(long epochMillis) throws SourceException {
        long[] entries = {0};
        try {
            query(OLD_HISTORY, statement -> statement.setTimestamp(1, new Timestamp(epochMillis)),
                    row -> entries[0] = row.getLong(1));
        } catch (SQLException failure) {
            throw SourceException.failed(failure);
        }
        return entries[0];
    }

    @Override
    public PlayerRecords players() throws SourceException {
        Players found;
        try {
            found = known();
        } catch (SQLException failure) {
            throw SourceException.failed(failure);
        }
        Iterator<String> uuids = found.uuids.iterator();
        return () -> {
            if (!uuids.hasNext()) return null;
            String uuid = uuids.next();
            try {
                return player(uuid, found.keysOf(uuid));
            } catch (SQLException | RuntimeException failure) {
                requireDatabase();
                throw new UnreadablePlayerException(uuid, failure);
            }
        };
    }

    private void requireDatabase() throws SourceException {
        if (queries.isCancelled()) throw SourceException.cancelled();
        if (!enabled.getAsBoolean()) throw SourceException.missing(ImportProblem.LITEBANS_MISSING, "LiteBans stopped");
        try {
            query(PROBE, statement -> { }, row -> { });
        } catch (SQLException | RuntimeException lost) {
            throw SourceException.failed(lost);
        }
    }

    SourcePlayer player(String uuid, List<String> storedKeys) throws SQLException {
        String marks = placeholders(storedKeys.size());
        Binder keys = statement -> {
            for (int index = 0; index < storedKeys.size(); index++) statement.setString(index + 1, storedKeys.get(index));
        };
        PlayerHistory history = new PlayerHistory();
        query(String.format(PLAYER_HISTORY, marks), keys, row -> {
            Timestamp date = row.getTimestamp("DATE");
            history.seen(row.getString("NAME"), row.getString("IP"), date != null ? date.getTime() : clock.getAsLong());
        });
        List<SourcePunishment> punishments = new ArrayList<>();
        for (LiteBansTable table : LiteBansTable.values()) {
            query(String.format(punishmentsOf(table), marks), keys, row -> punishments.add(punishment(table, row)));
        }
        return history.toPlayer(uuid, punishments);
    }

    private static String placeholders(int count) {
        StringBuilder marks = new StringBuilder();
        for (int index = 0; index < count; index++) marks.append(index == 0 ? "?" : ", ?");
        return marks.toString();
    }

    private SourcePunishment punishment(LiteBansTable table, ResultSet row) throws SQLException {
        long now = clock.getAsLong();
        long time = row.getLong("TIME");
        long issuedAt = time > 0 ? time : now;
        String reason = row.getString("REASON");
        String issuer = row.getString("BANNED_BY_NAME");
        SourcePunishment.SourcePunishmentBuilder punishment = SourcePunishment.builder()
                .id(table.importedId(row.getInt("ID")))
                .kind(table.getKind())
                .reason(hasText(reason) ? reason : defaultReason)
                .issuerName(hasText(issuer) ? issuer : Constants.DEFAULT_CONSOLE_NAME)
                .issuedAt(issuedAt);
        if (!table.isRemovable()) return punishment.build();

        long until = row.getLong("UNTIL");
        boolean lapsed = until > 0 && until <= now;
        boolean active = row.getBoolean("ACTIVE");
        String removerName = row.getString("REMOVED_BY_NAME");
        boolean removed = !EXPIRED_MARKER.equals(removerName)
                && (hasText(removerName) || hasText(row.getString("REMOVED_BY_UUID")));
        Timestamp removedAt = removed ? row.getTimestamp("REMOVED_BY_DATE") : null;
        return punishment
                .duration(SourcePunishment.durationBetween(issuedAt, until))
                .removal(removed ? new Removal(hasText(removerName) ? removerName : Constants.UNKNOWN,
                        removedAt != null ? removedAt.getTime() : null, null) : null)
                .endedEarly(!removed && !active && !lapsed)
                .inForce(active && !removed && !lapsed)
                .scope(scopeOf(row))
                .build();
    }

    private static PunishmentScope scopeOf(ResultSet row) throws SQLException {
        if (row.getBoolean("IPBAN")) return PunishmentScope.IP_WIDE;
        return isServerScoped(row.getString("SERVER_SCOPE")) ? PunishmentScope.SERVER_SCOPED : PunishmentScope.ACCOUNT;
    }

    @Override
    public boolean isReusable() {
        return !queries.isCancelled() && enabled.getAsBoolean();
    }

    @Override
    public void cancelRunning() {
        queries.cancel();
    }

    @Override
    public void close() {
        database.close();
    }

    private void query(String sql, Binder binder, RowReader reader) throws SQLException {
        queries.requireActive();
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            queries.read(statement, binder::bind, reader::read);
        }
    }

    private static boolean isServerScoped(@Nullable String scope) {
        return hasText(scope) && !EVERY_SERVER.equals(scope);
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isEmpty();
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private interface RowReader {
        void read(ResultSet row) throws SQLException;
    }

    private static final class Players {
        private final PlayerKeySet uuids = new PlayerKeySet();
        private final Map<String, Set<String>> otherSpellings = new HashMap<>();
        private SourceInventory inventory = SourceInventory.builder().build();

        private @Nullable String seen(@Nullable String stored) {
            String uuid = PlayerKeys.uuidOrNull(stored);
            if (uuid == null || stored == null) return null;
            uuids.add(uuid);
            if (!stored.equals(uuid)) otherSpellings.computeIfAbsent(uuid, unseen -> new LinkedHashSet<>()).add(stored);
            return uuid;
        }

        private List<String> keysOf(String uuid) {
            List<String> keys = new ArrayList<>();
            keys.add(uuid);
            keys.addAll(otherSpellings.getOrDefault(uuid, Collections.emptySet()));
            return keys;
        }
    }
}
