package gg.modl.backend.database.sql;

import org.jspecify.annotations.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

final class FakeLockServer {
    static final long OTHER_SESSION = 9_999;

    private static final String OWNERSHIP_QUERY = "SELECT IS_USED_LOCK(?) = CONNECTION_ID()";
    private static final String KILL = "KILL ";

    private final AtomicLong sessions = new AtomicLong();
    private final Set<Long> brokenClients = ConcurrentHashMap.newKeySet();
    private final Map<Long, BlockedCheck> blockedOwnershipChecks = new ConcurrentHashMap<>();
    @Nullable
    private volatile Long holder;

    DataSource connections() {
        return proxy(DataSource.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getConnection" -> connection(sessions.incrementAndGet());
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Nullable
    Long holder() {
        return holder;
    }

    void takeLockFor(long session) {
        holder = session;
    }

    void breakClientOf(long session) {
        brokenClients.add(session);
    }

    BlockedCheck blockOwnershipChecksOf(long session) {
        BlockedCheck blocked = new BlockedCheck(new CountDownLatch(1), new CountDownLatch(1));
        blockedOwnershipChecks.put(session, blocked);
        return blocked;
    }

    private Connection connection(long session) {
        return proxy(Connection.class, (proxy, method, arguments) -> {
            String name = method.getName();
            if (name.equals("close")) {
                if (!brokenClients.contains(session)) {
                    endSession(session);
                }
                return null;
            }
            requireReachable(session);
            return switch (name) {
                case "getCatalog" -> "modl";
                case "createStatement" -> statement(session, null);
                case "prepareStatement" -> statement(session, (String) arguments[0]);
                case "isClosed" -> false;
                default -> throw new UnsupportedOperationException(name);
            };
        });
    }

    private Object statement(long session, @Nullable String prepared) {
        Class<?> type = prepared == null ? Statement.class : PreparedStatement.class;
        return proxy(type, (proxy, method, arguments) -> switch (method.getName()) {
            case "setString", "close" -> null;
            case "execute" -> {
                execute(session, (String) arguments[0]);
                yield false;
            }
            case "executeQuery" -> query(session, prepared == null ? (String) arguments[0] : prepared);
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private void execute(long session, String sql) throws SQLException {
        requireReachable(session);
        if (sql.startsWith(KILL)) {
            endSession(Long.parseLong(sql.substring(KILL.length())));
        }
    }

    private ResultSet query(long session, String sql) throws SQLException {
        requireReachable(session);
        return switch (sql) {
            case "SELECT CONNECTION_ID()" -> row(session);
            case "SELECT GET_LOCK(?, 0)" -> row(grant(session) ? 1L : 0L);
            case OWNERSHIP_QUERY -> {
                awaitIfBlocked(session);
                requireReachable(session);
                yield row(Long.valueOf(session).equals(holder) ? 1L : 0L);
            }
            case "SELECT IS_USED_LOCK(?)" -> row(holder);
            case "SELECT RELEASE_LOCK(?)" -> row(release(session) ? 1L : 0L);
            default -> throw new UnsupportedOperationException(sql);
        };
    }

    private synchronized boolean grant(long session) {
        if (holder == null || holder == session) {
            holder = session;
            return true;
        }
        return false;
    }

    private synchronized boolean release(long session) {
        if (Long.valueOf(session).equals(holder)) {
            holder = null;
            return true;
        }
        return false;
    }

    private void endSession(long session) {
        release(session);
    }

    private void awaitIfBlocked(long session) throws SQLException {
        BlockedCheck blocked = blockedOwnershipChecks.get(session);
        if (blocked == null) {
            return;
        }
        blocked.entered().countDown();
        try {
            blocked.release().await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException(e);
        }
    }

    private void requireReachable(long session) throws SQLException {
        if (brokenClients.contains(session)) {
            throw new SQLException("Communications link failure");
        }
    }

    record BlockedCheck(CountDownLatch entered, CountDownLatch release) {
    }

    private static ResultSet row(@Nullable Long value) {
        boolean[] consumed = {false};
        boolean[] lastWasNull = {false};
        return proxy(ResultSet.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "next" -> {
                boolean first = !consumed[0];
                consumed[0] = true;
                yield first;
            }
            case "getLong" -> {
                lastWasNull[0] = value == null;
                yield value == null ? 0L : value;
            }
            case "getBoolean" -> {
                lastWasNull[0] = value == null;
                yield value != null && value != 0;
            }
            case "wasNull" -> lastWasNull[0];
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(FakeLockServer.class.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
