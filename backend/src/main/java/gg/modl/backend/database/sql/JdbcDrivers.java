package gg.modl.backend.database.sql;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class JdbcDrivers {
    private static final String MYSQL_DRIVER_PACKAGE = "com.mysql.cj.";
    private static final String MYSQL_CLEANUP_THREAD = "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread";
    private static final String MYSQL_CLEANUP_SHUTDOWN = "checkedShutdown";

    private JdbcDrivers() {
    }

    public static void releaseLoadedBy(ClassLoader classLoader) {
        Set<Driver> released = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Driver> pending = driversLoadedBy(classLoader, released);
        while (!pending.isEmpty()) {
            if (pending.stream().anyMatch(driver -> driver.getClass().getName().startsWith(MYSQL_DRIVER_PACKAGE))) {
                stopMysqlCleanupThread(classLoader);
            }
            pending.forEach(JdbcDrivers::deregister);
            released.addAll(pending);
            pending = driversLoadedBy(classLoader, released);
        }
    }

    private static List<Driver> driversLoadedBy(ClassLoader classLoader, Set<Driver> excluded) {
        return DriverManager.drivers()
            .filter(driver -> driver.getClass().getClassLoader() == classLoader && !excluded.contains(driver))
            .toList();
    }

    private static void deregister(Driver driver) {
        try {
            DriverManager.deregisterDriver(driver);
        } catch (SQLException | SecurityException e) {
            log.warn("Could not deregister the JDBC driver {}", driver.getClass().getName(), e);
        }
    }

    private static void stopMysqlCleanupThread(ClassLoader classLoader) {
        try {
            Class.forName(MYSQL_CLEANUP_THREAD, true, classLoader).getMethod(MYSQL_CLEANUP_SHUTDOWN).invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            log.debug("The MySQL abandoned-connection cleanup thread was not stopped", e);
        }
    }
}
