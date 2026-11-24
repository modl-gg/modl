package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.update.ArtifactKind;
import gg.modl.update.ReleaseArtifact;
import gg.modl.update.ReleaseVersion;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PayloadZips {
    static final ReleaseVersion BASE = ReleaseVersion.parse("3.0.0");

    private PayloadZips() {
    }

    static Path zip(Path directory, Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            addEntries(output, directory, "");
        }
        return zip;
    }

    static ReleaseArtifact pinned(Path zip, ReleaseVersion version) throws IOException {
        return ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, version, PayloadBuilder.sha256(Files.readAllBytes(zip)), Files.size(zip));
    }

    static BasePayload base(Path directory, Path archiveDirectory, PayloadArchives archives) throws IOException {
        Path zip = zip(directory, archiveDirectory.resolve(ArtifactKind.EMBEDDED.fileName(BASE)));
        ReleaseArtifact artifact = pinned(zip, BASE);
        return new BasePayload() {
            @Override
            public VerifiedArchive open(BooleanSupplier cancelled) throws IOException {
                return openInstalled();
            }

            @Override
            public VerifiedArchive openInstalled() throws IOException {
                return archives.open(zip, artifact, BASE);
            }
        };
    }

    static PayloadArchives archives(EmbeddedServerPaths paths, ServerLibraryDownloader downloader,
                                    PluginLogger logger) {
        return new PayloadArchives(paths.getLibraryDirectory(), downloader, paths.getDataDirectory().resolve("updates/run"), logger);
    }

    private static void addEntries(ZipOutputStream output, Path directory, String prefix) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                String name = prefix + child.getFileName();
                if (Files.isDirectory(child)) {
                    addEntries(output, child, name + "/");
                } else {
                    output.putNextEntry(new ZipEntry(name));
                    Files.copy(child, output);
                    output.closeEntry();
                }
            }
        }
    }
}
