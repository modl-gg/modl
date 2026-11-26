package gg.modl.minecraft.core.imports.sql;

import gg.modl.minecraft.core.imports.support.FileTrees;
import gg.modl.minecraft.core.util.OwnerOnlyFiles;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class JdbcConnections implements AutoCloseable {
    private static final String LOADER_PREFIX = "modl-import-";
    private static final AtomicInteger LOADS = new AtomicInteger();

    private final DriverLibraries libraries;
    private final Path nativeFolder;
    private final Map<JdbcDriver, Driver> loaded = new EnumMap<>(JdbcDriver.class);
    private final Set<String> loaders = new LinkedHashSet<>();
    private int open;
    private boolean closed;

    public JdbcConnections(DriverLibraries libraries, Path nativeFolder) {
        this.libraries = libraries;
        this.nativeFolder = nativeFolder;
    }

    public SqlSession session(JdbcDriver kind, String url, Properties properties, Preparation preparation,
                              Runnable afterClose) throws SQLException {
        enter();
        Connection connection;
        try {
            connection = connect(kind, url, properties);
            prepare(connection, preparation);
        } catch (SQLException | RuntimeException | Error failure) {
            leave();
            throw failure;
        }
        return SqlSession.owning(connection, () -> {
            try {
                afterClose.run();
            } finally {
                leave();
            }
        });
    }

    Connection connect(JdbcDriver kind, String url, Properties properties) throws SQLException {
        Driver existing = loadedDriver(kind);
        Connection connection = existing != null ? existing.connect(url, properties) : firstConnect(kind, url, properties);
        if (connection == null) throw new SQLException("The " + kind + " driver does not accept " + url);
        return connection;
    }

    @Override
    public void close() {
        List<String> released;
        synchronized (this) {
            closed = true;
            released = open == 0 ? takeLoaders() : new ArrayList<>();
        }
        release(released);
    }

    private synchronized void enter() throws SQLException {
        if (closed) throw new SQLException("The import database drivers have been released");
        open++;
    }

    private void leave() {
        List<String> released;
        synchronized (this) {
            open--;
            released = open == 0 ? takeLoaders() : new ArrayList<>();
        }
        release(released);
        if (!released.isEmpty()) discardNativeFiles();
    }

    private synchronized void discardNativeFiles() {
        if (open == 0) FileTrees.deleteQuietly(nativeFolder);
    }

    private List<String> takeLoaders() {
        List<String> taken = new ArrayList<>(loaders);
        loaders.clear();
        loaded.clear();
        return taken;
    }

    private void release(List<String> released) {
        for (String loaderId : released) libraries.release(loaderId);
    }

    private synchronized @Nullable Driver loadedDriver(JdbcDriver kind) {
        return loaded.get(kind);
    }

    private static void prepare(Connection connection, Preparation preparation) throws SQLException {
        try {
            preparation.prepare(connection);
        } catch (SQLException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    private Connection firstConnect(JdbcDriver kind, String url, Properties properties) throws SQLException {
        Map<String, String> scoped = kind.propertiesWhileLoading(nativeFolder);
        Map<String, String> previous = new HashMap<>();
        try {
            if (!scoped.isEmpty()) createNativeFolder();
            scoped.forEach((name, value) -> {
                previous.put(name, System.getProperty(name));
                System.setProperty(name, value);
            });
            Driver driver = load(kind);
            return driver.connect(url, properties);
        } catch (IOException unwritable) {
            throw new SQLException("The folder " + nativeFolder + " for the " + kind + " driver could not be created", unwritable);
        } finally {
            previous.forEach((name, value) -> {
                if (value == null) System.clearProperty(name);
                else System.setProperty(name, value);
            });
        }
    }

    private void createNativeFolder() throws IOException {
        OwnerOnlyFiles.createDirectories(nativeFolder);
    }

    private Driver load(JdbcDriver kind) throws SQLException {
        String loaderId = LOADER_PREFIX + kind.name() + "-" + LOADS.incrementAndGet();
        synchronized (this) {
            loaders.add(loaderId);
        }
        try {
            ClassLoader loader = libraries.isolatedLoader(loaderId, kind.getLibrary());
            Driver created = Class.forName(kind.getClassName(), true, loader).asSubclass(Driver.class)
                    .getDeclaredConstructor().newInstance();
            synchronized (this) {
                Driver raced = loaded.putIfAbsent(kind, created);
                return raced != null ? raced : created;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            throw new SQLException("The " + kind + " database driver could not be loaded: " + unavailable, unavailable);
        }
    }

    @FunctionalInterface
    public interface Preparation {
        void prepare(Connection connection) throws SQLException;
    }
}
