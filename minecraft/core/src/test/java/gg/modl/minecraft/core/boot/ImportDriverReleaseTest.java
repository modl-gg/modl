package gg.modl.minecraft.core.boot;

import com.alessiodp.libby.Library;
import com.alessiodp.libby.LibraryManager;
import com.alessiodp.libby.classloader.IsolatedClassLoader;
import com.alessiodp.libby.logging.LogLevel;
import com.alessiodp.libby.logging.adapters.LogAdapter;
import gg.modl.minecraft.api.LibraryRecord;
import gg.modl.minecraft.core.boot.drivers.RegisteredDrivers;
import gg.modl.minecraft.core.boot.drivers.TinyTestDriver;
import gg.modl.minecraft.core.imports.sql.DriverLibraries;
import gg.modl.minecraft.core.imports.sql.JdbcConnections;
import gg.modl.minecraft.core.imports.sql.JdbcDriver;
import gg.modl.minecraft.core.imports.sql.SqlSession;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportDriverReleaseTest {
    private static final String TINY_DRIVER = TinyTestDriver.class.getName();
    private static final String TINY_DRIVER_FILE = TINY_DRIVER.replace('.', '/') + ".class";
    private static final String LOADER_ID = "modl-import-TINY-1";

    @TempDir
    Path temp;

    @Test
    void aReleasedDriverIsDeregisteredItsLoaderClosedAndNothingKeepsIt() throws Exception {
        LibraryRecord tiny = tinyDriverLibrary();
        TestLibraryManager manager = new TestLibraryManager(temp);
        RuntimeLibraryLoader libraries = new RuntimeLibraryLoader(manager, PlatformType.SPIGOT, getClass().getClassLoader());

        ClassLoader loader = libraries.isolatedLoader(LOADER_ID, tiny);
        Class.forName(TINY_DRIVER, true, loader);
        IntSupplier registered = registrationProbe(loader);
        assertEquals(1, registered.getAsInt());
        assertNotNull(loader.getResource(TINY_DRIVER_FILE));

        libraries.release(LOADER_ID);

        assertEquals(0, registered.getAsInt());
        assertNull(loader.getResource(TINY_DRIVER_FILE));
        assertNull(manager.getIsolatedClassLoaderById(LOADER_ID));
        ClassLoader again = libraries.isolatedLoader(LOADER_ID, tiny);
        assertNotSame(loader, again);
        assertNotNull(again.getResource(TINY_DRIVER_FILE));
        libraries.release(LOADER_ID);
    }

    @Test
    void theDownloadedDriversStayOnlyWhileAnImportSessionIsOpen() throws Exception {
        RecordingLibraries libraries = new RecordingLibraries(h2Libraries());
        JdbcConnections connections = new JdbcConnections(libraries, temp.resolve("native"));

        SqlSession preview = session(connections, "preview");
        SqlSession run = session(connections, "run");
        ClassLoader loader = libraries.only();
        IntSupplier registered = registrationProbe(loader);
        assertEquals(1, registered.getAsInt());

        preview.close();
        assertTrue(libraries.released.isEmpty());
        assertEquals(1, registered.getAsInt());

        Path extracted = temp.resolve("native").resolve("sqlite-native.so");
        Files.createDirectories(extracted.getParent());
        Files.write(extracted, new byte[]{1});
        run.close();
        assertEquals(1, libraries.released.size());
        assertEquals(0, registered.getAsInt());
        assertNull(loader.getResource("org/h2/Driver.class"));
        assertFalse(Files.exists(extracted.getParent()));
    }

    @Test
    void coreShutdownReleasesTheDriversOnceTheLastSessionEnds() throws Exception {
        RecordingLibraries libraries = new RecordingLibraries(h2Libraries());
        JdbcConnections connections = new JdbcConnections(libraries, temp.resolve("native"));
        SqlSession open = session(connections, "open");
        IntSupplier registered = registrationProbe(libraries.only());

        connections.close();
        assertTrue(libraries.released.isEmpty());
        assertEquals(1, registered.getAsInt());

        open.close();
        assertEquals(1, libraries.released.size());
        assertEquals(0, registered.getAsInt());
        assertThrows(SQLException.class, () -> session(connections, "late"));
        assertEquals(1, libraries.loaders.size());
    }

    @Test
    void aNewImportAfterTheReleaseLoadsAFreshDriver() throws Exception {
        RecordingLibraries libraries = new RecordingLibraries(h2Libraries());
        JdbcConnections connections = new JdbcConnections(libraries, temp.resolve("native"));
        session(connections, "first").close();

        try (SqlSession second = session(connections, "second")) {
            assertEquals(2, libraries.loaders.size());
            assertNotSame(libraries.loaders.get(0), libraries.loaders.get(1));
            assertEquals(1, registrationProbe(libraries.loaders.get(1)).getAsInt());
            second.query("SELECT 1", row -> assertEquals(1, row.getInt(1)));
        }
        assertEquals(2, libraries.released.size());
        connections.close();
    }

    private static SqlSession session(JdbcConnections connections, String name) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "sa");
        properties.setProperty("password", "");
        return connections.session(JdbcDriver.H2_LEGACY, "jdbc:h2:mem:" + name, properties, connection -> { }, () -> { });
    }

    private RuntimeLibraryLoader h2Libraries() throws IOException, URISyntaxException {
        TestLibraryManager manager = new TestLibraryManager(temp);
        LibraryRecord h2 = JdbcDriver.H2_LEGACY.getLibrary();
        Path jar = Paths.get(Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path target = manager.libraryFolder().resolve(libraryOf(h2).getPath());
        Files.createDirectories(target.getParent());
        Files.copy(jar, target);
        return new RuntimeLibraryLoader(manager, PlatformType.SPIGOT, getClass().getClassLoader());
    }

    private LibraryRecord tinyDriverLibrary() throws IOException, NoSuchAlgorithmException {
        Path jar = temp.resolve("tiny-driver.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream output = new JarOutputStream(file);
             InputStream bytes = TinyTestDriver.class.getClassLoader().getResourceAsStream(TINY_DRIVER_FILE)) {
            assertNotNull(bytes);
            output.putNextEntry(new JarEntry(TINY_DRIVER_FILE));
            byte[] buffer = new byte[4096];
            for (int read = bytes.read(buffer); read >= 0; read = bytes.read(buffer)) output.write(buffer, 0, read);
            output.closeEntry();
        }
        String checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        LibraryRecord record = LibraryRecord.of("gg{}modl{}test", "tiny-driver", "1.0", "tiny-driver-1.0.jar", checksum);
        Path target = temp.resolve("lib").resolve(libraryOf(record).getPath());
        Files.createDirectories(target.getParent());
        Files.move(jar, target);
        return record;
    }

    private static Library libraryOf(LibraryRecord record) {
        return Library.builder().groupId(record.getGroupId()).artifactId(record.getArtifactId()).version(record.getVersion())
                .checksumFromBase64(record.getChecksum()).build();
    }

    private static IntSupplier registrationProbe(ClassLoader loader) throws IOException, ReflectiveOperationException {
        String file = RegisteredDrivers.class.getName().replace('.', '/') + ".class";
        try (InputStream bytes = RegisteredDrivers.class.getClassLoader().getResourceAsStream(file)) {
            assertNotNull(bytes);
            Class<?> probe = ((IsolatedClassLoader) loader).defineClass(RegisteredDrivers.class.getName(), bytes);
            return (IntSupplier) probe.getDeclaredConstructor().newInstance();
        }
    }

    private static final class RecordingLibraries implements DriverLibraries {
        private final DriverLibraries delegate;
        private final List<ClassLoader> loaders = new CopyOnWriteArrayList<>();
        private final List<String> released = new CopyOnWriteArrayList<>();

        private RecordingLibraries(DriverLibraries delegate) {
            this.delegate = delegate;
        }

        @Override
        public ClassLoader isolatedLoader(String loaderId, LibraryRecord library) {
            ClassLoader loader = delegate.isolatedLoader(loaderId, library);
            if (!loaders.contains(loader)) loaders.add(loader);
            return loader;
        }

        @Override
        public void release(String loaderId) {
            released.add(loaderId);
            delegate.release(loaderId);
        }

        private ClassLoader only() {
            assertEquals(1, loaders.size(), new ArrayList<>(loaders).toString());
            return loaders.get(0);
        }
    }

    private static final class TestLibraryManager extends LibraryManager {
        private TestLibraryManager(Path dataDirectory) {
            super(new SilentLogAdapter(), dataDirectory, "lib");
        }

        private Path libraryFolder() {
            return saveDirectory;
        }

        @Override
        protected void addToClasspath(Path file) {
        }
    }

    private static final class SilentLogAdapter implements LogAdapter {
        @Override
        public void log(LogLevel level, String message) {
        }

        @Override
        public void log(LogLevel level, String message, Throwable throwable) {
        }
    }
}
