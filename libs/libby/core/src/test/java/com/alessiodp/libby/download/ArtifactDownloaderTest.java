package com.alessiodp.libby.download;

import com.alessiodp.libby.logging.LogLevel;
import com.alessiodp.libby.logging.Logger;
import com.alessiodp.libby.logging.adapters.LogAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactDownloaderTest {
    private static final String ARTIFACT_PATH = "/maven2/org/example/lib/1.0/lib-1.0.jar";
    private static final byte[] CONTENT = "library classes".getBytes(StandardCharsets.UTF_8);
    private static final int SERVICE_UNAVAILABLE = 503;
    private static final int FOUND = 302;

    @TempDir
    Path directory;

    private ArtifactServer server;
    private final List<String> warnings = new ArrayList<>();
    private final ArtifactDownloader downloader = new ArtifactDownloader(new Logger(new RecordingAdapter()),
            RetryPolicy.of(3, 0));

    @BeforeEach
    void startServer() throws IOException {
        server = ArtifactServer.start();
        server.serve(ARTIFACT_PATH, CONTENT);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void writesAVerifiedDownloadAndReportsItsProgress() throws Exception {
        Path target = directory.resolve("lib-1.0.jar");
        List<Long> progress = new ArrayList<>();

        downloader.download("org.example:lib:1.0", urls(ARTIFACT_PATH), sha256(CONTENT), CONTENT.length, target,
                progress::add);

        assertArrayEquals(CONTENT, Files.readAllBytes(target));
        assertEquals(Long.valueOf(CONTENT.length), progress.get(progress.size() - 1));
        assertEquals(Collections.singletonList(target), filesIn(directory));
    }

    @Test
    void rejectsADownloadThatDoesNotMatchItsChecksumWithoutRetryingAndKeepsTheExistingFile() throws Exception {
        Path target = directory.resolve("lib-1.0.jar");
        byte[] previous = "previous".getBytes(StandardCharsets.UTF_8);
        Files.write(target, previous);

        IOException failure = assertThrows(IOException.class, () -> downloader.download("org.example:lib:1.0",
                urls(ARTIFACT_PATH), sha256("other".getBytes(StandardCharsets.UTF_8)), CONTENT.length, target,
                TransferListener.NONE));

        assertTrue(failure.getMessage().contains("SHA-256"), failure.getMessage());
        assertArrayEquals(previous, Files.readAllBytes(target));
        assertEquals(Collections.singletonList(target), filesIn(directory));
        assertEquals(1, server.requests(ARTIFACT_PATH));
        assertTrue(warnings.contains("*** INVALID CHECKSUM ***"));
    }

    @Test
    void stopsReadingADownloadThatIsLargerThanExpected() {
        Path target = directory.resolve("lib-1.0.jar");

        IOException failure = assertThrows(IOException.class, () -> downloader.download("org.example:lib:1.0",
                urls(ARTIFACT_PATH), sha256(CONTENT), CONTENT.length - 1, target, TransferListener.NONE));

        assertTrue(failure.getMessage().contains("larger than its expected"), failure.getMessage());
        assertFalse(Files.exists(target));
        assertEquals(1, server.requests(ARTIFACT_PATH));
    }

    @Test
    void reportsALocalWriteFailureWithoutRetryingTheDownload() throws Exception {
        Path notADirectory = Files.write(directory.resolve("occupied"), CONTENT);

        assertThrows(LocalFileException.class, () -> downloader.download("org.example:lib:1.0", urls(ARTIFACT_PATH),
                sha256(CONTENT), CONTENT.length, notADirectory.resolve("lib-1.0.jar"), TransferListener.NONE));
        assertEquals(0, server.requests(ARTIFACT_PATH));
    }

    @Test
    void retriesTransientFailures() throws Exception {
        server.failNext(ARTIFACT_PATH, SERVICE_UNAVAILABLE);
        server.failNext(ARTIFACT_PATH, SERVICE_UNAVAILABLE);
        Path target = directory.resolve("lib-1.0.jar");

        downloader.download("org.example:lib:1.0", urls(ARTIFACT_PATH), sha256(CONTENT), CONTENT.length, target,
                TransferListener.NONE);

        assertArrayEquals(CONTENT, Files.readAllBytes(target));
        assertEquals(3, server.requests(ARTIFACT_PATH));
    }

    @Test
    void givesUpAfterTheRetryBudgetAndNamesTheFailure() {
        for (int attempt = 0; attempt < 3; attempt++) server.failNext(ARTIFACT_PATH, SERVICE_UNAVAILABLE);
        Path target = directory.resolve("lib-1.0.jar");

        IOException failure = assertThrows(IOException.class, () -> downloader.download("org.example:lib:1.0",
                urls(ARTIFACT_PATH), sha256(CONTENT), CONTENT.length, target, TransferListener.NONE));

        assertTrue(failure.getMessage().contains("503"), failure.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void refusesARedirectItCannotFollowWithoutRetryingOrSavingItsBody() {
        server.failNext(ARTIFACT_PATH, FOUND);
        Path target = directory.resolve("lib-1.0.jar");

        IOException failure = assertThrows(IOException.class, () -> downloader.download("org.example:lib:1.0",
                urls(ARTIFACT_PATH), sha256(CONTENT), CONTENT.length, target, TransferListener.NONE));

        assertTrue(failure.getMessage().contains("answered HTTP 302"), failure.getMessage());
        assertEquals(1, server.requests(ARTIFACT_PATH));
        assertFalse(Files.exists(target));
    }

    @Test
    void movesOnToTheNextUrlWhenAnArtifactIsMissing() throws Exception {
        String missing = "/mirror/org/example/lib/1.0/lib-1.0.jar";
        Path target = directory.resolve("lib-1.0.jar");

        downloader.download("org.example:lib:1.0", Arrays.asList(server.url(missing), server.url(ARTIFACT_PATH)),
                sha256(CONTENT), CONTENT.length, target, TransferListener.NONE);

        assertEquals(1, server.requests(missing));
        assertArrayEquals(CONTENT, Files.readAllBytes(target));
    }

    @Test
    void reportsTheFailureOfAnUrlThatServedTheArtifactRatherThanALaterMissingOne() {
        String missing = "/mirror/org/example/lib/1.0/lib-1.0.jar";

        IOException failure = assertThrows(IOException.class, () -> downloader.download("org.example:lib:1.0",
                Arrays.asList(server.url(ARTIFACT_PATH), server.url(missing)),
                sha256("other".getBytes(StandardCharsets.UTF_8)), CONTENT.length, directory.resolve("lib-1.0.jar"),
                TransferListener.NONE));

        assertTrue(failure.getMessage().contains("SHA-256"), failure.getMessage());
    }

    @Test
    void reportsAMissingArtifactWithItsUrl() {
        String missing = "/maven2/org/example/absent/1.0/absent-1.0.jar";

        IOException failure = assertThrows(IOException.class, () -> downloader.download("org.example:absent:1.0",
                urls(missing), sha256(CONTENT), CONTENT.length, directory.resolve("absent-1.0.jar"), TransferListener.NONE));

        assertTrue(failure.getMessage().contains("not found at " + server.url(missing)), failure.getMessage());
        assertEquals(1, server.requests(missing));
    }

    @Test
    void stopsWhenTheDownloadingThreadIsInterrupted() throws Exception {
        Path target = directory.resolve("lib-1.0.jar");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> downloader.download("org.example:lib:1.0",
                    urls(ARTIFACT_PATH), sha256(CONTENT), CONTENT.length, target, TransferListener.NONE));
        } finally {
            Thread.interrupted();
        }
        assertFalse(Files.exists(target));
        assertEquals(0, server.requests(ARTIFACT_PATH));
    }

    private List<String> urls(String path) {
        return Collections.singletonList(server.url(path));
    }

    private static List<Path> filesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.sorted().collect(Collectors.toList());
        }
    }

    private static byte[] sha256(byte[] content) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }

    private final class RecordingAdapter implements LogAdapter {
        @Override
        public void log(LogLevel level, String message) {
            if (level == LogLevel.WARN) warnings.add(message);
        }

        @Override
        public void log(LogLevel level, String message, Throwable throwable) {
            log(level, message);
        }
    }
}
