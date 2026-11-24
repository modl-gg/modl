package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.ArtifactDownloader;
import com.alessiodp.libby.download.RetryPolicy;
import com.alessiodp.libby.logging.Logger;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedPayloadTest {
    private static final byte[] SERVER_JAR = "server-classes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SPRING_JAR = "spring-classes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NATIVE_JAR = "native-transport-classes".getBytes(StandardCharsets.UTF_8);
    private static final long PARTIAL_FILE_TIMEOUT_MILLIS = 10_000;

    @TempDir
    Path temp;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private MavenRepositoryFixture repository;
    private Path libraries;
    private MavenArtifact springCore;
    private MavenArtifact nativeTransport;

    @BeforeEach
    void publishLibraries() throws IOException {
        repository = MavenRepositoryFixture.start();
        libraries = temp.resolve("plugins/modl/server/lib");
        springCore = MavenArtifact.parse("org.springframework:spring-core:7.1.0");
        nativeTransport = MavenArtifact.parse("io.netty:netty-transport-native-epoll:4.2.20.Final:linux-x86_64");
        repository.publish(springCore, SPRING_JAR);
        repository.publish(nativeTransport, NATIVE_JAR);
    }

    @AfterEach
    void stopRepository() {
        repository.close();
    }

    @Test
    void packsTheServerJarAndDownloadsEveryOtherLibraryOnFirstStart() throws IOException {
        InstalledPayload installed = install(standardPayload(), EmbeddedPayloadTest::neverCancelled);

        assertEquals("3.0.0", installed.getVersion());
        assertEquals(1, installed.getExtractedCount());
        assertEquals(2, installed.getDownloadedCount());
        assertEquals(3, installed.getJars().size());
        assertArrayEquals(SERVER_JAR, Files.readAllBytes(libraries.resolve("modl-server-3.0.0-plain.jar")));
        assertArrayEquals(SPRING_JAR, Files.readAllBytes(libraries.resolve("spring-core-7.1.0.jar")));
        assertArrayEquals(NATIVE_JAR, Files.readAllBytes(libraries.resolve(nativeTransport.fileName())));
        assertTrue(logger.infos().contains("Downloading modl server 3.0.0 libraries from Maven Central: 2 files, 0.0 MB."),
                logger.infos().toString());
    }

    @Test
    void reusesEveryLibraryWhoseHashStillMatchesWithoutDownloadingIt() throws IOException {
        PayloadSource payload = standardPayload();
        install(payload, EmbeddedPayloadTest::neverCancelled);

        InstalledPayload second = install(payload, EmbeddedPayloadTest::neverCancelled);

        assertFalse(second.changedAnything());
        assertEquals(1, repository.requests(springCore));
        assertEquals(1, repository.requests(nativeTransport));
    }

    @Test
    void downloadsTamperedLibrariesAgainAndRemovesStaleFiles() throws IOException {
        PayloadSource payload = standardPayload();
        install(payload, EmbeddedPayloadTest::neverCancelled);
        Files.write(libraries.resolve("spring-core-7.1.0.jar"), "tampered".getBytes(StandardCharsets.UTF_8));
        Path previousVersion = Files.write(libraries.resolve("spring-core-7.0.0.jar"), SPRING_JAR);
        Path abandonedDownload = Files.write(libraries.resolve("spring-core-7.1.0.jar.123.part"), SPRING_JAR);

        InstalledPayload installed = install(payload, EmbeddedPayloadTest::neverCancelled);

        assertEquals(1, installed.getDownloadedCount());
        assertArrayEquals(SPRING_JAR, Files.readAllBytes(libraries.resolve("spring-core-7.1.0.jar")));
        assertFalse(Files.exists(previousVersion));
        assertFalse(Files.exists(abandonedDownload));
    }

    @Test
    void rejectsADownloadThatDoesNotMatchItsIndexHash() throws IOException {
        repository.publish(springCore, "substituted".getBytes(StandardCharsets.UTF_8));

        EmbeddedServerException failure = assertThrows(EmbeddedServerException.class,
                () -> install(standardPayload(), EmbeddedPayloadTest::neverCancelled));

        assertTrue(failure.getMessage().contains("org.springframework:spring-core:7.1.0"), failure.getMessage());
        assertTrue(failure.getMessage().contains("SHA-256"), failure.getMessage());
        assertTrue(failure.getMessage().contains("tries the download again on the next start"), failure.getMessage());
        assertFalse(Files.exists(libraries.resolve("spring-core-7.1.0.jar")));
        assertEquals(0, partialFiles());
    }

    @Test
    void namesTheLibraryMavenCentralDoesNotHave() throws IOException {
        MavenArtifact missing = MavenArtifact.parse("org.example:missing:1.0");
        PayloadSource payload = new PayloadBuilder(temp.resolve("payload"))
                .packed("modl-server-3.0.0-plain.jar", SERVER_JAR)
                .central(missing, SPRING_JAR)
                .write();

        EmbeddedServerException failure = assertThrows(EmbeddedServerException.class, () -> install(payload, EmbeddedPayloadTest::neverCancelled));

        assertTrue(failure.getMessage().contains("could not download org.example:missing:1.0 from Maven Central (not found at "),
                failure.getMessage());
    }

    @Test
    void oneFailedDownloadStopsTheOthersAndLeavesNoPartialFile() throws Exception {
        MavenArtifact missing = MavenArtifact.parse("org.example:missing:1.0");
        CountDownLatch release = repository.holdHalfway(nativeTransport);
        repository.respondAfter(missing, repository.arrivalOf(nativeTransport));
        PayloadSource payload = new PayloadBuilder(temp.resolve("payload"))
                .packed("modl-server-3.0.0-plain.jar", SERVER_JAR)
                .central(nativeTransport, NATIVE_JAR)
                .central(missing, SPRING_JAR)
                .write();

        assertThrows(EmbeddedServerException.class, () -> install(payload, EmbeddedPayloadTest::neverCancelled));
        release.countDown();

        awaitNoPartialFiles();
        assertFalse(Files.exists(libraries.resolve(nativeTransport.fileName())));
    }

    @Test
    void aCancelledStartStopsDownloadingAndInstallsNothingHalfDone() throws Exception {
        CountDownLatch release = repository.holdHalfway(springCore);
        CountDownLatch springRequested = repository.arrivalOf(springCore);
        CountDownLatch nativeRequested = repository.arrivalOf(nativeTransport);

        assertThrows(CancellationException.class, () -> install(standardPayload(),
                () -> springRequested.getCount() == 0 && nativeRequested.getCount() == 0));
        release.countDown();

        awaitNoPartialFiles();
        assertFalse(Files.exists(libraries.resolve("spring-core-7.1.0.jar")));
    }

    @Test
    void refusesAPackedLibraryThatDoesNotMatchItsIndexHash() throws IOException {
        PayloadSource payload = new PayloadBuilder(temp.resolve("payload"))
                .line("packed " + PayloadBuilder.sha256(SERVER_JAR) + " " + SERVER_JAR.length + " modl-server-3.0.0-plain.jar")
                .write();
        Files.createDirectories(temp.resolve("payload/lib"));
        Files.write(temp.resolve("payload/lib/modl-server-3.0.0-plain.jar"), "corrupt".getBytes(StandardCharsets.UTF_8));

        IOException failure = assertThrows(IOException.class, () -> install(payload, EmbeddedPayloadTest::neverCancelled));

        assertTrue(failure.getMessage().contains("modl-server-3.0.0-plain.jar"), failure.getMessage());
        assertFalse(Files.exists(libraries.resolve("modl-server-3.0.0-plain.jar")));
        assertEquals(0, partialFiles());
    }

    @Test
    void rejectsIndexEntriesThatEscapeTheLibraryDirectory() throws IOException {
        PayloadSource payload = new PayloadBuilder(temp.resolve("payload"))
                .line("packed " + PayloadBuilder.sha256(SERVER_JAR) + " " + SERVER_JAR.length + " ../outside.jar")
                .write();

        assertThrows(IOException.class, () -> install(payload, EmbeddedPayloadTest::neverCancelled));
        assertFalse(Files.exists(temp.resolve("plugins/modl/server/outside.jar")));
    }

    @Test
    void rejectsAMissingPayload() {
        IOException failure = assertThrows(IOException.class, () -> install(name -> null, EmbeddedPayloadTest::neverCancelled));

        assertTrue(failure.getMessage().contains(EmbeddedPayload.VERSION_FILE), failure.getMessage());
    }

    @Test
    void retainedLibrariesSurviveUntilThePayloadIsPruned() throws Exception {
        Files.createDirectories(libraries);
        Path previousServer = Files.write(libraries.resolve("modl-server-2.9.0-plain.jar"), SERVER_JAR);
        MavenCentralDownloader downloader = new MavenCentralDownloader(
                new ArtifactDownloader(new Logger(new DebugLogAdapter(logger)), RetryPolicy.of(2, 0)),
                repository.repositoryUrl(), 2);
        EmbeddedPayload payload = new EmbeddedPayload(standardPayload(), libraries, downloader, logger);

        InstalledPayload installed = payload.install(new DownloadProgress(logger, new StartupProgress()), EmbeddedPayloadTest::neverCancelled,
                Collections.singleton("modl-server-2.9.0-plain.jar"));
        assertTrue(Files.exists(previousServer));

        payload.pruneLibrariesExcept(installed.libraryFileNames());
        assertFalse(Files.exists(previousServer));
    }

    private PayloadSource standardPayload() throws IOException {
        return new PayloadBuilder(temp.resolve("payload"))
                .packed("modl-server-3.0.0-plain.jar", SERVER_JAR)
                .central(springCore, SPRING_JAR)
                .central(nativeTransport, NATIVE_JAR)
                .write();
    }

    private InstalledPayload install(PayloadSource source, BooleanSupplier cancelled) throws IOException {
        MavenCentralDownloader downloader = new MavenCentralDownloader(
                new ArtifactDownloader(new Logger(new DebugLogAdapter(logger)), RetryPolicy.of(2, 0)),
                repository.repositoryUrl(), 2);
        return new EmbeddedPayload(source, libraries, downloader, logger)
                .install(new DownloadProgress(logger, new StartupProgress()), cancelled);
    }

    private void awaitNoPartialFiles() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PARTIAL_FILE_TIMEOUT_MILLIS);
        while (partialFiles() > 0 && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(0, partialFiles());
    }

    private long partialFiles() throws IOException {
        if (!Files.isDirectory(libraries)) return 0;
        try (Stream<Path> files = Files.list(libraries)) {
            List<String> partial = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".part"))
                    .collect(Collectors.toList());
            return partial.size();
        }
    }

    private static boolean neverCancelled() {
        return false;
    }
}
