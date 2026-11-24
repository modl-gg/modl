package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.ArtifactDownloader;
import com.alessiodp.libby.logging.Logger;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.update.ArtifactMismatchException;
import gg.modl.update.ReleaseKeys;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class PinnedPayload implements BasePayload {
    private static final String ARCHIVE_PATTERN = "modl-embedded-*";

    private final ClassLoader pluginLoader;
    private final Path serverDirectory;
    private final PayloadArchives archives;
    private final Supplier<PinnedDownload> downloads;
    private final PluginLogger logger;
    private volatile @Nullable PayloadPin pin;
    private volatile @Nullable PinnedDownload download;

    PinnedPayload(ClassLoader pluginLoader, Path serverDirectory, PayloadArchives archives, Supplier<PinnedDownload> downloads,
                  PluginLogger logger) {
        this.pluginLoader = pluginLoader;
        this.serverDirectory = serverDirectory;
        this.archives = archives;
        this.downloads = downloads;
        this.logger = logger;
    }

    public static PinnedPayload forPlugin(ClassLoader pluginLoader, EmbeddedServerPaths paths, Map<String, String> forwardedSwitches,
                                          ReleaseKeys keys, PayloadArchives archives, StartupProgress progress,
                                          String configPath, PluginLogger logger) {
        ArtifactDownloader downloader = new ArtifactDownloader(new Logger(new DebugLogAdapter(logger)));
        Path serverDirectory = paths.getServerDirectory();
        return new PinnedPayload(pluginLoader, serverDirectory, archives,
                () -> new PinnedDownload(serverDirectory, ReleaseSource.of(forwardedSwitches), keys, downloader, progress,
                        configPath, logger),
                logger);
    }

    @Override
    public VerifiedArchive open(BooleanSupplier cancelled) throws IOException {
        PayloadPin pinned = pin();
        PinnedDownload releases = download();
        Path archive = archiveOf(pinned);
        if (!Files.isRegularFile(archive)) releases.download(pinned, archive, cancelled);
        VerifiedArchive opened;
        try {
            opened = archives.open(archive, pinned.artifact(), pinned.version());
        } catch (ArtifactMismatchException mismatch) {
            throw new EmbeddedServerException(archive + " does not match this modl plugin jar (" + mismatch.getMessage()
                    + "). Delete it so modl downloads it again, or replace it: " + releases.manualRemedy(pinned), mismatch);
        }
        removeOtherArchives(archive);
        return opened;
    }

    @Override
    public VerifiedArchive openInstalled() throws IOException {
        PayloadPin pinned = pin();
        return archives.open(archiveOf(pinned), pinned.artifact(), pinned.version());
    }

    private Path archiveOf(PayloadPin pinned) {
        return serverDirectory.resolve(pinned.artifact().file());
    }

    private PayloadPin pin() throws IOException {
        PayloadPin loaded = pin;
        if (loaded == null) {
            loaded = PayloadPin.bundledIn(pluginLoader);
            pin = loaded;
        }
        return loaded;
    }

    private PinnedDownload download() {
        PinnedDownload resolved = download;
        if (resolved == null) {
            resolved = downloads.get();
            download = resolved;
        }
        return resolved;
    }

    private void removeOtherArchives(Path archive) {
        try (DirectoryStream<Path> archives = Files.newDirectoryStream(serverDirectory, ARCHIVE_PATTERN)) {
            for (Path other : archives) {
                if (!other.equals(archive) && Files.isRegularFile(other)) Files.deleteIfExists(other);
            }
        } catch (IOException inUse) {
            logger.debug("Could not remove older built-in panel archives from " + serverDirectory + ": " + inUse.getMessage());
        }
    }
}
