package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.ArtifactDownloader;
import com.alessiodp.libby.download.LocalFileException;
import com.alessiodp.libby.download.TransferListener;
import gg.modl.minecraft.core.connection.ConnectionYaml;
import gg.modl.minecraft.core.util.Hex;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.update.ArtifactKind;
import gg.modl.update.ReleaseArtifact;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseManifest;
import gg.modl.update.ReleaseVerificationException;
import gg.modl.update.SignedRelease;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class PinnedDownload {
    private static final String ENVELOPE_DOWNLOAD_SUFFIX = ".download";

    private final Path serverDirectory;
    private final ReleaseSource source;
    private final ReleaseKeys keys;
    private final ArtifactDownloader downloader;
    private final StartupProgress progress;
    private final String configPath;
    private final PluginLogger logger;

    PinnedDownload(Path serverDirectory, ReleaseSource source, ReleaseKeys keys, ArtifactDownloader downloader,
                   StartupProgress progress, String configPath, PluginLogger logger) {
        this.serverDirectory = serverDirectory;
        this.source = source;
        this.keys = keys;
        this.downloader = downloader;
        this.progress = progress;
        this.configPath = configPath;
        this.logger = logger;
    }

    void download(PayloadPin pin, Path archive, BooleanSupplier cancelled) throws IOException {
        if (!keys.isEmpty()) requireSignedRelease(pin, cancelled);
        ReleaseArtifact artifact = pin.artifact();
        String url = archiveUrl(pin);
        logger.info("Downloading the built-in panel (" + artifact.file() + ", " + Megabytes.of(artifact.size()) + ") from "
                + url + ".");
        fetch(pin, artifact.file(), url, Hex.decode(artifact.sha256()), artifact.size(), archive,
                new ArchiveDownloadProgress(logger, progress, cancelled, artifact.size()));
    }

    String archiveUrl(PayloadPin pin) {
        return source.fileUrl(pin.version(), pin.artifact().file());
    }

    String manualRemedy(PayloadPin pin) {
        return "Download " + archiveUrl(pin) + " by hand (for a plugin jar you built yourself, take " + pin.artifact().file()
                + " from the same build's dist folder) and put it in " + serverDirectory + ", or set "
                + ConnectionYaml.EMBEDDED_DOWNLOAD_URL + " in " + configPath + " to a mirror of the modl releases.";
    }

    private void requireSignedRelease(PayloadPin pin, BooleanSupplier cancelled) throws IOException {
        String url = source.fileUrl(pin.version(), SignedRelease.ENVELOPE_FILE);
        Path envelope = serverDirectory.resolve(SignedRelease.ENVELOPE_FILE + ENVELOPE_DOWNLOAD_SUFFIX);
        try {
            fetch(pin, SignedRelease.ENVELOPE_FILE, url, null, SignedRelease.MAX_ENVELOPE_BYTES, envelope, silent(cancelled));
            requireListsPin(pin, url, Files.readAllBytes(envelope));
        } finally {
            Files.deleteIfExists(envelope);
        }
    }

    private void requireListsPin(PayloadPin pin, String url, byte[] envelope) {
        ReleaseManifest manifest;
        try {
            manifest = SignedRelease.verify(envelope, keys, GsonJsonMaps.INSTANCE).manifest();
        } catch (ReleaseVerificationException invalid) {
            throw new EmbeddedServerException("The modl release at " + url + " does not verify (" + invalid.getMessage() + "). "
                    + manualRemedy(pin), invalid);
        }
        if (!manifest.version().equals(pin.version()) || !pin.artifact().equals(manifest.artifact(ArtifactKind.EMBEDDED))) {
            throw new EmbeddedServerException("The modl release at " + url + " does not list the " + pin.artifact().file()
                    + " this plugin jar pins. " + manualRemedy(pin));
        }
    }

    private void fetch(PayloadPin pin, String file, String url, byte @Nullable [] sha256, long size, Path target,
                       TransferListener listener) {
        try {
            downloader.download(file, Collections.singletonList(url), sha256, size, target, listener);
        } catch (LocalFileException unwritable) {
            throw new EmbeddedServerException("The built-in panel could not save " + file + " (" + unwritable.getMessage()
                    + "). Free disk space or fix the permissions of " + serverDirectory + "; modl tries again on the next start.",
                    unwritable);
        } catch (IOException failed) {
            throw new EmbeddedServerException("The built-in panel could not download " + file + " from " + url + " ("
                    + failed.getMessage() + "). " + manualRemedy(pin), failed);
        }
    }

    private TransferListener silent(BooleanSupplier cancelled) {
        return attemptBytes -> {
            if (cancelled.getAsBoolean()) throw new CancellationException("The built-in panel start was cancelled");
            progress.advance();
        };
    }
}
