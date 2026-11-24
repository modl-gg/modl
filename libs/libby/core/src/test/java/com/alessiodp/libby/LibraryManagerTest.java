package com.alessiodp.libby;

import com.alessiodp.libby.logging.LogLevel;
import com.alessiodp.libby.logging.adapters.LogAdapter;
import com.alessiodp.libby.relocation.RelocationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryManagerTest {
    private static final String ARTIFACT_PATH = "org/example/lib/1.0/lib-1.0.jar";
    private static final byte[] CONTENT = "library classes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    private Path repository;
    private Path libraries;
    private TestLibraryManager manager;

    @BeforeEach
    void createRepository() throws IOException {
        repository = temp.resolve("repository");
        Files.createDirectories(repository.resolve(ARTIFACT_PATH).getParent());
        Files.write(repository.resolve(ARTIFACT_PATH), CONTENT);
        manager = new TestLibraryManager(temp.resolve("plugin"));
        manager.addRepository(repository.toUri().toString());
        libraries = temp.resolve("plugin/lib");
    }

    @Test
    void reusesAnIntactLibraryWithoutDownloadingItAgain() throws Exception {
        Path first = manager.downloadLibrary(library(CONTENT));
        Files.delete(repository.resolve(ARTIFACT_PATH));

        Path second = manager.downloadLibrary(library(CONTENT));

        assertEquals(first, second);
        assertArrayEquals(CONTENT, Files.readAllBytes(second));
    }

    @Test
    void downloadsALibraryAgainWhenTheCachedCopyNoLongerMatchesItsChecksum() throws Exception {
        Path file = manager.downloadLibrary(library(CONTENT));
        Files.write(file, "tampered".getBytes(StandardCharsets.UTF_8));

        manager.downloadLibrary(library(CONTENT));

        assertArrayEquals(CONTENT, Files.readAllBytes(file));
    }

    @Test
    void failsWithTheReasonWhenNoRepositoryServesAMatchingFile() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.downloadLibrary(library("expected".getBytes(StandardCharsets.UTF_8))));

        assertTrue(failure.getMessage().contains("SHA-256"), failure.getMessage());
        assertFalse(Files.exists(libraries.resolve(ARTIFACT_PATH)));
    }

    @Test
    void keepsOnlyTheRetainedLibrariesAndTheRelocationToolsAcrossUpgrades() throws Exception {
        Library library = relocatedLibrary(CONTENT);
        Path current = manager.downloadLibrary(library(CONTENT));
        Path relocated = libraries.resolve(library.getRelocatedPath());
        Path relocationTool = libraries.resolve(RelocationHelper.LIBRARIES.get(0).getPath());
        Path olderVersion = libraries.resolve("org/example/lib/0.9/lib-0.9.jar");
        Path staleRelocation = current.resolveSibling("lib-1.0.jar-relocated-12345.jar");
        Path abandonedDownload = current.resolveSibling("lib-1.0.jar.4711.part");
        Path droppedLibrary = libraries.resolve("com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar");
        for (Path file : new Path[]{relocated, relocationTool, olderVersion, staleRelocation, abandonedDownload, droppedLibrary}) {
            Files.createDirectories(file.getParent());
            Files.write(file, CONTENT);
        }

        manager.retainOnly(Collections.singletonList(library));

        assertTrue(Files.exists(current));
        assertTrue(Files.exists(relocated));
        assertTrue(Files.exists(relocationTool));
        assertFalse(Files.exists(olderVersion.getParent()));
        assertFalse(Files.exists(staleRelocation));
        assertFalse(Files.exists(abandonedDownload));
        assertFalse(Files.exists(libraries.resolve("com")));
        assertTrue(Files.isDirectory(libraries));
    }

    @Test
    void retainingIntoAMissingDirectoryDoesNothing() {
        manager.retainOnly(Collections.<Library>emptyList());

        assertFalse(Files.exists(libraries));
    }

    @Test
    void reusesTheCachedRelocatedCopyOfAnUnchangedLibrary() throws Exception {
        Library library = relocatedLibrary(CONTENT);
        manager.downloadLibrary(library(CONTENT));
        Path cached = libraries.resolve(library.getRelocatedPath());
        Files.write(cached, "relocated classes".getBytes(StandardCharsets.UTF_8));

        Path loaded = manager.downloadLibrary(library);

        assertEquals(cached, loaded);
        assertArrayEquals("relocated classes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(loaded));
    }

    @Test
    void relocatedCopiesAreKeyedByTheLibraryChecksum() throws Exception {
        Library current = relocatedLibrary(CONTENT);
        Library changed = relocatedLibrary("changed".getBytes(StandardCharsets.UTF_8));

        assertNotEquals(current.getRelocatedPath(), changed.getRelocatedPath());
        assertEquals(current.getRelocatedPath(), relocatedLibrary(CONTENT).getRelocatedPath());
    }

    private static Library library(byte[] content) throws NoSuchAlgorithmException {
        return builder(content).build();
    }

    private static Library relocatedLibrary(byte[] content) throws NoSuchAlgorithmException {
        return builder(content).relocate("org{}example", "shaded{}example").build();
    }

    private static Library.Builder builder(byte[] content) throws NoSuchAlgorithmException {
        return Library.builder()
                .groupId("org{}example")
                .artifactId("lib")
                .version("1.0")
                .checksum(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static final class TestLibraryManager extends LibraryManager {
        private TestLibraryManager(Path dataDirectory) {
            super(new SilentAdapter(), dataDirectory, "lib");
        }

        @Override
        protected void addToClasspath(Path file) {
        }
    }

    private static final class SilentAdapter implements LogAdapter {
        @Override
        public void log(LogLevel level, String message) {
        }

        @Override
        public void log(LogLevel level, String message, Throwable throwable) {
        }
    }
}
