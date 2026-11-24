package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.update.ReleaseArtifact;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.UpdateFiles;
import gg.modl.update.VerifiedCopy;

import java.io.IOException;
import java.nio.file.Path;

public final class PayloadArchives {
    private final Path libraryDirectory;
    private final ServerLibraryDownloader downloader;
    private final Path runDirectory;
    private final PluginLogger logger;

    PayloadArchives(Path libraryDirectory, ServerLibraryDownloader downloader, Path runDirectory, PluginLogger logger) {
        this.libraryDirectory = libraryDirectory;
        this.downloader = downloader;
        this.runDirectory = runDirectory;
        this.logger = logger;
    }

    public static PayloadArchives forPaths(EmbeddedServerPaths paths, PluginLogger logger) {
        return new PayloadArchives(paths.getLibraryDirectory(), MavenCentralDownloader.create(logger),
                new UpdateFiles(paths.getDataDirectory(), GsonJsonMaps.INSTANCE).runDirectory(), logger);
    }

    VerifiedArchive open(Path zip, ReleaseArtifact artifact, ReleaseVersion version) throws IOException {
        VerifiedCopy copy = VerifiedCopy.of(zip, artifact, runDirectory);
        try {
            EmbeddedPayload payload = new EmbeddedPayload(PayloadSource.archive(copy.file()), libraryDirectory, downloader, logger);
            String contained = payload.version();
            if (!version.toString().equals(contained)) {
                throw new IOException(zip.getFileName() + " contains modl " + contained + ", not modl " + version);
            }
            return new VerifiedArchive(payload, copy);
        } catch (IOException | RuntimeException invalid) {
            copy.close();
            throw invalid;
        }
    }
}
