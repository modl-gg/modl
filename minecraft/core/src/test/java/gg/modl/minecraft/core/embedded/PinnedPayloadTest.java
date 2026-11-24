package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.ArtifactDownloader;
import com.alessiodp.libby.download.RetryPolicy;
import com.alessiodp.libby.logging.Logger;
import gg.modl.minecraft.core.connection.ConnectionYaml;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.update.ArtifactKind;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.SignedRelease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static gg.modl.minecraft.core.embedded.EmbeddedReleases.PATCH;
import static gg.modl.minecraft.core.embedded.EmbeddedReleases.PLUGIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedPayloadTest {
    private static final String ARCHIVE = ArtifactKind.EMBEDDED.fileName(PLUGIN);
    private static final String CONFIG_PATH = "plugins/modl/connection.yml";

    @TempDir
    Path temp;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private EmbeddedServerPaths paths;
    private EmbeddedReleases releases;
    private ReleaseMirrorFixture mirror;
    private URLClassLoader pluginJar;
    private byte[] archive;
    private byte[] envelope;

    @BeforeEach
    void pinThePluginRelease() throws Exception {
        paths = EmbeddedServerPaths.forPluginDataFolder(temp.resolve("modl"));
        releases = new EmbeddedReleases(temp.resolve("work"), paths, logger);
        Path published = releases.publish(PLUGIN);
        archive = Files.readAllBytes(published);
        envelope = Files.readAllBytes(releases.files().envelope(PLUGIN));
        Path jar = temp.resolve("plugin-jar");
        Path pin = jar.resolve(PayloadPin.RESOURCE);
        Files.createDirectories(pin.getParent());
        Files.write(pin, (PayloadPin.HEADER + "\nversion " + PLUGIN + "\nsha256 " + PayloadBuilder.sha256(archive) + "\nsize "
                + archive.length + "\n").getBytes(StandardCharsets.UTF_8));
        pluginJar = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
        mirror = ReleaseMirrorFixture.start();
    }

    @AfterEach
    void stopMirror() throws IOException {
        mirror.close();
        pluginJar.close();
    }

    @Test
    void aDroppedInArchiveStartsWithoutAnyDownload() throws Exception {
        dropIn(archive);

        try (VerifiedArchive opened = payload(ReleaseKeys.parse("")).open(PinnedPayloadTest::neverCancelled)) {
            assertEquals(PLUGIN.toString(), opened.payload().version());
        }
        assertEquals(0, mirror.totalRequests());
    }

    @Test
    void theFirstStartDownloadsThePinnedArchiveAndLaterStartsReuseIt() throws Exception {
        mirror.publish(PLUGIN.toString(), ARCHIVE, archive);
        PinnedPayload payload = payload(ReleaseKeys.parse(""));

        payload.open(PinnedPayloadTest::neverCancelled).close();
        payload.open(PinnedPayloadTest::neverCancelled).close();

        assertEquals(1, mirror.requests(PLUGIN.toString(), ARCHIVE));
        assertTrue(Files.isRegularFile(paths.getServerDirectory().resolve(ARCHIVE)));
        assertTrue(logger.infos().stream().anyMatch(line -> line.startsWith("Downloading the built-in panel (" + ARCHIVE)
                && line.contains(mirror.baseUrl() + "/download/v3.0.0/" + ARCHIVE)), logger.infos().toString());
    }

    @Test
    void aTamperedDownloadIsRefusedAndNeverKept() throws Exception {
        byte[] tampered = archive.clone();
        tampered[tampered.length / 2] ^= 1;
        mirror.publish(PLUGIN.toString(), ARCHIVE, tampered);

        EmbeddedServerException refused = assertThrows(EmbeddedServerException.class,
                () -> payload(ReleaseKeys.parse("")).open(PinnedPayloadTest::neverCancelled));

        assertTrue(refused.getMessage().contains("does not match its pinned SHA-256"), refused.getMessage());
        assertManualRemedy(refused);
        assertEquals(0, serverDirectoryFiles());
    }

    @Test
    void aDroppedInArchiveThatDoesNotMatchThePinIsRefused() throws Exception {
        byte[] tampered = archive.clone();
        tampered[0] ^= 1;
        dropIn(tampered);

        EmbeddedServerException refused = assertThrows(EmbeddedServerException.class,
                () -> payload(ReleaseKeys.parse("")).open(PinnedPayloadTest::neverCancelled));

        assertTrue(refused.getMessage().contains("does not match this modl plugin jar"), refused.getMessage());
        assertManualRemedy(refused);
    }

    @Test
    void anUnreachableReleaseNamesTheUrlTheFileAndTheFolder() {
        EmbeddedServerException refused = assertThrows(EmbeddedServerException.class,
                () -> payload(ReleaseKeys.parse("")).open(PinnedPayloadTest::neverCancelled));

        assertTrue(refused.getMessage().startsWith("The built-in panel could not download " + ARCHIVE), refused.getMessage());
        assertManualRemedy(refused);
    }

    @Test
    void withReleaseKeysADownloadIsCheckedAgainstTheSignedRelease() throws Exception {
        mirror.publish(PLUGIN.toString(), ARCHIVE, archive);
        mirror.publish(PLUGIN.toString(), SignedRelease.ENVELOPE_FILE, envelope);

        payload(releases.keys()).open(PinnedPayloadTest::neverCancelled).close();

        assertEquals(1, mirror.requests(PLUGIN.toString(), SignedRelease.ENVELOPE_FILE));
        assertEquals(1, serverDirectoryFiles());
    }

    @Test
    void withReleaseKeysASignedReleaseOfOtherBytesStopsTheDownload() throws Exception {
        releases.publish(PATCH);
        mirror.publish(PLUGIN.toString(), ARCHIVE, archive);
        mirror.publish(PLUGIN.toString(), SignedRelease.ENVELOPE_FILE, Files.readAllBytes(releases.files().envelope(PATCH)));

        EmbeddedServerException refused = assertThrows(EmbeddedServerException.class,
                () -> payload(releases.keys()).open(PinnedPayloadTest::neverCancelled));

        assertTrue(refused.getMessage().contains("does not list the " + ARCHIVE), refused.getMessage());
        assertEquals(0, mirror.requests(PLUGIN.toString(), ARCHIVE));
    }

    @Test
    void withReleaseKeysAnUnsignedReleaseStopsTheDownload() {
        mirror.publish(PLUGIN.toString(), ARCHIVE, archive);

        EmbeddedServerException refused = assertThrows(EmbeddedServerException.class,
                () -> payload(releases.keys()).open(PinnedPayloadTest::neverCancelled));

        assertTrue(refused.getMessage().contains(SignedRelease.ENVELOPE_FILE), refused.getMessage());
        assertEquals(0, mirror.requests(PLUGIN.toString(), ARCHIVE));
    }

    @Test
    void withReleaseKeysADroppedInArchiveNeedsOnlyThePin() throws Exception {
        dropIn(archive);

        payload(releases.keys()).open(PinnedPayloadTest::neverCancelled).close();

        assertEquals(0, mirror.totalRequests());
    }

    @Test
    void aCancelledStartStopsTheDownload() {
        mirror.publish(PLUGIN.toString(), ARCHIVE, archive);

        assertThrows(CancellationException.class, () -> payload(ReleaseKeys.parse("")).open(() -> true));
        assertFalse(Files.exists(paths.getServerDirectory().resolve(ARCHIVE)));
    }

    @Test
    void aVerifiedDownloadRemovesTheArchivesOfOtherVersions() throws Exception {
        Path older = olderArchive();
        mirror.publish(PLUGIN.toString(), ARCHIVE, archive);

        payload(ReleaseKeys.parse("")).open(PinnedPayloadTest::neverCancelled).close();

        assertFalse(Files.exists(older));
    }

    @Test
    void aVerifiedDropInRemovesTheArchivesOfOtherVersions() throws Exception {
        Path older = olderArchive();
        dropIn(archive);

        payload(ReleaseKeys.parse("")).open(PinnedPayloadTest::neverCancelled).close();

        assertFalse(Files.exists(older));
    }

    @Test
    void aRefusedArchiveKeepsTheArchivesOfOtherVersions() throws Exception {
        Path older = olderArchive();
        byte[] tampered = archive.clone();
        tampered[0] ^= 1;
        dropIn(tampered);

        assertThrows(EmbeddedServerException.class, () -> payload(ReleaseKeys.parse("")).open(PinnedPayloadTest::neverCancelled));

        assertTrue(Files.exists(older));
    }

    @Test
    void theInstalledArchiveIsOpenedWithoutADownload() throws Exception {
        mirror.publish(PLUGIN.toString(), ARCHIVE, archive);
        PinnedPayload payload = payload(ReleaseKeys.parse(""));

        assertThrows(IOException.class, payload::openInstalled);
        dropIn(archive);
        payload.openInstalled().close();

        assertEquals(0, mirror.totalRequests());
    }

    private PinnedPayload payload(ReleaseKeys keys) {
        ReleaseSource source = ReleaseSource.of(Collections.singletonMap(ForwardedSwitches.BASE_URL, mirror.baseUrl()));
        ArtifactDownloader downloader = new ArtifactDownloader(new Logger(new DebugLogAdapter(logger)), RetryPolicy.of(1, 0));
        PinnedDownload download = new PinnedDownload(paths.getServerDirectory(), source, keys, downloader, new StartupProgress(),
                CONFIG_PATH, logger);
        return new PinnedPayload(pluginJar, paths.getServerDirectory(), releases.archives(), () -> download, logger);
    }

    private Path olderArchive() throws IOException {
        Path older = paths.getServerDirectory().resolve("modl-embedded-2.9.0.zip");
        Files.createDirectories(older.getParent());
        return Files.write(older, new byte[] {1});
    }

    private void dropIn(byte[] content) throws IOException {
        Files.createDirectories(paths.getServerDirectory());
        Files.write(paths.getServerDirectory().resolve(ARCHIVE), content);
    }

    private void assertManualRemedy(EmbeddedServerException refused) {
        String message = refused.getMessage();
        assertTrue(message.contains("Download " + mirror.baseUrl() + "/download/v3.0.0/" + ARCHIVE + " by hand"), message);
        assertTrue(message.contains("the same build's dist folder"), message);
        assertTrue(message.contains("put it in " + paths.getServerDirectory()), message);
        assertTrue(message.contains(ConnectionYaml.EMBEDDED_DOWNLOAD_URL + " in " + CONFIG_PATH), message);
    }

    private long serverDirectoryFiles() throws IOException {
        if (!Files.isDirectory(paths.getServerDirectory())) return 0;
        try (Stream<Path> files = Files.list(paths.getServerDirectory())) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static boolean neverCancelled() {
        return false;
    }
}
