package gg.modl.minecraft.core.boot;

import com.alessiodp.libby.Library;
import com.alessiodp.libby.LibraryManager;
import com.alessiodp.libby.logging.LogLevel;
import com.alessiodp.libby.logging.adapters.LogAdapter;
import com.alessiodp.libby.relocation.Relocation;
import gg.modl.minecraft.api.LibraryRecord;
import gg.modl.minecraft.core.Libraries;
import gg.modl.minecraft.core.imports.sql.JdbcDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLibraryLoaderTest {
    @TempDir
    Path temp;

    @Test
    void theProtoLibraryIsCopiedFromThePluginJarAndRelocatedLikeTheShadedPluginClasses() throws IOException {
        try (URLClassLoader pluginJar = pluginJarWithProto()) {
            Library proto = new RuntimeLibraryLoader(new RecordingLibraryManager(temp), PlatformType.SPIGOT, pluginJar)
                    .toLibrary(Libraries.PROTO);

            URL expected = pluginJar.getResource(Libraries.BUNDLED_DIRECTORY + "/proto-" + Libraries.PROTO.getVersion() + ".jar");
            assertEquals(Collections.singletonList(expected.toString()), new ArrayList<>(proto.getUrls()));
            assertEquals("gg.modl:proto:" + Libraries.PROTO.getVersion(), proto.toString());
            assertTrue(proto.getRelocations().contains(new Relocation("com{}google{}protobuf", "gg{}modl{}libs{}protobuf")));
            assertTrue(proto.getRelocations().contains(new Relocation("com{}buf{}validate", "gg{}modl{}libs{}bufvalidate")));
        }
    }

    @Test
    void aPluginJarWithoutTheProtoLibraryFailsWithARemedy() throws IOException {
        try (URLClassLoader emptyJar = new URLClassLoader(new URL[]{temp.toUri().toURL()}, null)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new RuntimeLibraryLoader(new RecordingLibraryManager(temp), PlatformType.SPIGOT, emptyJar)
                            .toLibrary(Libraries.PROTO));

            assertTrue(failure.getMessage().contains("Download the full modl plugin jar"), failure.getMessage());
        }
    }

    @Test
    void loadsThePlatformLibrariesInOrderAndKeepsOnlyThemInTheLibraryDirectory() throws IOException {
        RecordingLibraryManager manager = new RecordingLibraryManager(temp);
        try (URLClassLoader pluginJar = pluginJarWithProto()) {
            new RuntimeLibraryLoader(manager, PlatformType.VELOCITY, pluginJar).loadAll();
        }

        List<String> expected = Libraries.forPlatform(PlatformType.VELOCITY).stream()
                .map(record -> record.getGroupId().replace("{}", ".") + ":" + record.getArtifactId().replace("{}", ".")
                        + ":" + record.getVersion())
                .collect(Collectors.toList());
        List<String> retained = new ArrayList<>(expected);
        for (LibraryRecord driver : JdbcDriver.libraries()) {
            retained.add(driver.getGroupId().replace("{}", ".") + ":" + driver.getArtifactId().replace("{}", ".") + ":"
                    + driver.getVersion() + (driver.getClassifier() == null ? "" : ":" + driver.getClassifier()));
        }
        assertEquals(expected, manager.loaded);
        assertEquals(Collections.singletonList(retained), manager.retained);
        assertTrue(manager.getRepositories().contains("https://repo1.maven.org/maven2/"));
    }

    private URLClassLoader pluginJarWithProto() throws IOException {
        Path proto = temp.resolve("plugin/" + Libraries.BUNDLED_DIRECTORY + "/proto-" + Libraries.PROTO.getVersion() + ".jar");
        Files.createDirectories(proto.getParent());
        Files.write(proto, new byte[]{1});
        return new URLClassLoader(new URL[]{temp.resolve("plugin").toUri().toURL()}, getClass().getClassLoader());
    }

    private static final class RecordingLibraryManager extends LibraryManager {
        private final List<String> loaded = new ArrayList<>();
        private final List<List<String>> retained = new ArrayList<>();

        private RecordingLibraryManager(Path dataDirectory) {
            super(new SilentLogAdapter(), dataDirectory, "lib");
        }

        @Override
        public void loadLibrary(Library library) {
            loaded.add(library.toString());
        }

        @Override
        public void retainOnly(Collection<Library> libraries) {
            retained.add(libraries.stream().map(Library::toString).collect(Collectors.toList()));
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
