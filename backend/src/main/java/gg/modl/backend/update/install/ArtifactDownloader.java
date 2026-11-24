package gg.modl.backend.update.install;

import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.check.ReleaseHttpClient;
import gg.modl.update.ArtifactMismatchException;
import gg.modl.update.ReleaseArtifact;
import gg.modl.update.SignedRelease;
import gg.modl.update.UpdateFiles;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.IntConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ArtifactDownloader {
    static final Duration BUDGET = Duration.ofMinutes(15);
    private static final String PARTIAL_SUFFIX = ".partial";

    private final ReleaseHttpClient http;
    private final UpdateProperties properties;

    public Path download(UpdateFiles files, SignedRelease release, ReleaseArtifact artifact, IntConsumer percent) throws IOException {
        Path directory = files.versionDirectory(release.version());
        Files.createDirectories(directory);
        Path target = directory.resolve(artifact.file());
        Path partial = directory.resolve(artifact.file() + PARTIAL_SUFFIX);
        URI source = URI.create(properties.baseUrl() + "/download/v" + release.version() + "/" + artifact.file());
        String sha256;
        try {
            sha256 = http.download(source, partial, artifact.size(), BUDGET,
                written -> percent.accept((int) Math.min(100, written * 100 / artifact.size())));
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        if (!sha256.equals(artifact.sha256())) {
            Files.deleteIfExists(partial);
            throw new ArtifactMismatchException("The download of " + artifact.file() + " does not match the SHA-256 the release lists");
        }
        moveAtomically(partial, target);
        files.writeAtomically(files.envelope(release.version()), release.envelope());
        return target;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
