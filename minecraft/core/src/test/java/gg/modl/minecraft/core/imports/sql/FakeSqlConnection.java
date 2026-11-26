package gg.modl.minecraft.core.imports.sql;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeSqlConnection {
    private final long rowsPerQuery;
    private final long millisPerRow;
    private final boolean honorsCancel;
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AtomicBoolean validationFails = new AtomicBoolean();
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger rows = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final List<Integer> validationTimeouts = new CopyOnWriteArrayList<>();
    private final List<String> cancellingThreads = new CopyOnWriteArrayList<>();

    private FakeSqlConnection(long rowsPerQuery, long millisPerRow, boolean honorsCancel) {
        this.rowsPerQuery = rowsPerQuery;
        this.millisPerRow = millisPerRow;
        this.honorsCancel = honorsCancel;
    }

    public static FakeSqlConnection empty() {
        return new FakeSqlConnection(0, 0, true);
    }

    public static FakeSqlConnection endless(long millisPerRow, boolean honorsCancel) {
        return new FakeSqlConnection(Long.MAX_VALUE, millisPerRow, honorsCancel);
    }

    public void dies() {
        valid.set(false);
    }

    public void failsValidation() {
        validationFails.set(true);
    }

    public int prepared() {
        return prepared.get();
    }

    public int rows() {
        return rows.get();
    }

    public int closes() {
        return closes.get();
    }

    public List<Integer> validationTimeouts() {
        return validationTimeouts;
    }

    public List<String> cancellingThreads() {
        return cancellingThreads;
    }

    public SqlSession session() throws SQLException {
        return SqlSession.borrowing(connection(), closes::incrementAndGet);
    }

    public Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "isValid" -> {
                            validationTimeouts.add((Integer) arguments[0]);
                            if (validationFails.get()) throw new SQLException("Connection reset");
                            yield valid.get();
                        }
                        case "getMetaData" -> metadata();
                        case "prepareStatement" -> {
                            prepared.incrementAndGet();
                            yield statement();
                        }
                        case "getAutoCommit" -> true;
                        case "close", "rollback" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private DatabaseMetaData metadata() {
        return (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, arguments) -> {
                    if ("getDatabaseProductName".equals(method.getName())) return "MariaDB";
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private PreparedStatement statement() {
        AtomicBoolean cancelled = new AtomicBoolean();
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "executeQuery" -> resultSet(cancelled);
                        case "cancel" -> {
                            cancellingThreads.add(Thread.currentThread().getName());
                            cancelled.set(true);
                            yield null;
                        }
                        case "setFetchSize", "setObject", "setString", "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private ResultSet resultSet(AtomicBoolean cancelled) {
        AtomicInteger read = new AtomicInteger();
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "next" -> next(cancelled, read);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private boolean next(AtomicBoolean cancelled, AtomicInteger read) throws SQLException, InterruptedException {
        if (honorsCancel && cancelled.get()) throw new SQLException("Query execution was interrupted");
        if (read.get() >= rowsPerQuery) return false;
        if (millisPerRow > 0) TimeUnit.MILLISECONDS.sleep(millisPerRow);
        read.incrementAndGet();
        rows.incrementAndGet();
        return true;
    }
}
