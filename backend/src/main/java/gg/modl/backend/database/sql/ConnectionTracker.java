package gg.modl.backend.database.sql;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

public final class ConnectionTracker {
    private static final String CLOSE = "close";

    private final Map<ConnectionRole, AtomicInteger> open = new EnumMap<>(ConnectionRole.class);

    public ConnectionTracker() {
        for (ConnectionRole role : ConnectionRole.values()) {
            open.put(role, new AtomicInteger());
        }
    }

    private AtomicInteger counter(ConnectionRole role) {
        AtomicInteger counter = open.get(role);
        if (counter == null) {
            throw new IllegalStateException("Every connection role has a counter from construction, " + role + " has none");
        }
        return counter;
    }

    public DataSource track(DataSource dataSource, ConnectionRole role) {
        return new TrackedDataSource(dataSource, this, role);
    }

    public int openConnections() {
        return open.entrySet().stream()
            .filter(entry -> entry.getKey() != ConnectionRole.LOCK)
            .mapToInt(entry -> entry.getValue().get())
            .sum();
    }

    public int openConnections(ConnectionRole role) {
        return counter(role).get();
    }

    Connection tracked(Connection connection, ConnectionRole role) {
        AtomicInteger counter = counter(role);
        counter.incrementAndGet();
        AtomicBoolean closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(ConnectionTracker.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
                try {
                    return method.invoke(connection, arguments);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                } finally {
                    if (CLOSE.equals(method.getName()) && closed.compareAndSet(false, true)) {
                        counter.decrementAndGet();
                    }
                }
            });
    }
}
