package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.DatabaseProvider;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeLiteBansDatabase implements DatabaseProvider {
    private final List<Map<String, Object>> history = new ArrayList<>();
    static final String BANS = "{bans}", MUTES = "{mutes}", WARNINGS = "{warnings}", KICKS = "{kicks}";
    private static final String[] TABLES = {BANS, MUTES, WARNINGS, KICKS};

    private final Map<String, List<Map<String, Object>>> punishments = new HashMap<>();
    private final Set<String> unreadablePlayers = new LinkedHashSet<>();
    private final AtomicInteger closes = new AtomicInteger();
    private volatile boolean down;

    FakeLiteBansDatabase() {
        for (String table : TABLES) punishments.put(table, new ArrayList<>());
    }

    FakeLiteBansDatabase joined(String uuid, String name, String ip, long at) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("UUID", uuid);
        row.put("NAME", name);
        row.put("IP", ip);
        row.put("DATE", new Timestamp(at));
        history.add(row);
        return this;
    }

    Map<String, Object> punished(String table, int id, String uuid, long time, long until) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", id);
        row.put("UUID", uuid);
        row.put("REASON", "Griefing");
        row.put("BANNED_BY_NAME", "Moderator");
        row.put("TIME", time);
        row.put("UNTIL", until);
        row.put("ACTIVE", true);
        row.put("IPBAN", false);
        row.put("SERVER_SCOPE", "*");
        row.put("REMOVED_BY_UUID", null);
        row.put("REMOVED_BY_NAME", null);
        row.put("REMOVED_BY_DATE", null);
        punishments.get(table).add(row);
        return row;
    }

    void makeUnreadable(String uuid) {
        unreadablePlayers.add(uuid);
    }

    void goDown() {
        down = true;
    }

    int closes() {
        return closes.get();
    }

    @Override
    public void close() {
        closes.incrementAndGet();
    }

    @Override
    public PreparedStatement prepareStatement(String query) {
        Map<Integer, Object> bound = new HashMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "setString", "setTimestamp" -> {
                            bound.put((Integer) arguments[0], arguments[1]);
                            yield null;
                        }
                        case "executeQuery" -> resultSet(rowsFor(query, bound));
                        case "close", "cancel" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private List<Map<String, Object>> rowsFor(String query, Map<Integer, Object> bound) throws SQLException {
        Object parameter = bound.get(1);
        if (down || bound.values().stream().anyMatch(unreadablePlayers::contains)) {
            throw new SQLException("Communications link failure");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (query.startsWith("SELECT COUNT(DISTINCT UUID) FROM {history}")) {
            Map<String, Object> count = new HashMap<>();
            count.put("1", history.stream().map(row -> row.get("UUID")).distinct().count());
            rows.add(count);
        } else if (query.startsWith("SELECT DISTINCT UUID FROM {history}")) {
            rows.addAll(history);
        } else if (query.startsWith("SELECT COUNT(*) FROM {history}")) {
            long older = history.stream().filter(row -> ((Timestamp) row.get("DATE")).before((Timestamp) parameter)).count();
            Map<String, Object> count = new HashMap<>();
            count.put("1", older);
            rows.add(count);
        } else if (query.startsWith("SELECT NAME, IP, DATE FROM {history} WHERE UUID IN (")) {
            addRowsOf(history, bound.values(), rows);
        } else if (!query.endsWith("WHERE 1 = 0")) {
            addPunishments(query, bound.values(), rows);
        }
        return rows;
    }

    private void addPunishments(String query, Collection<Object> keys, List<Map<String, Object>> rows) throws SQLException {
        for (String table : TABLES) {
            if (!query.contains(" FROM " + table)) continue;
            if (query.contains("WHERE UUID IN (")) addRowsOf(punishments.get(table), keys, rows);
            else rows.addAll(punishments.get(table));
            return;
        }
        throw new SQLException("Unexpected query: " + query);
    }

    private static void addRowsOf(List<Map<String, Object>> table, Collection<Object> keys, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : table) {
            if (keys.contains(row.get("UUID"))) rows.add(row);
        }
    }

    private ResultSet resultSet(List<Map<String, Object>> rows) {
        Iterator<Map<String, Object>> remaining = rows.iterator();
        Map<String, Object> current = new HashMap<>();
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    String name = method.getName();
                    if ("close".equals(name)) return null;
                    if ("next".equals(name)) {
                        if (!remaining.hasNext()) return false;
                        current.clear();
                        current.putAll(remaining.next());
                        return true;
                    }
                    Object value = current.get(String.valueOf(arguments[0]));
                    return switch (name) {
                        case "getString", "getTimestamp" -> value;
                        case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                        case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                        case "getBoolean" -> Boolean.TRUE.equals(value);
                        default -> throw new UnsupportedOperationException(name);
                    };
                });
    }
}
