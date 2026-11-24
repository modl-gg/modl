package gg.modl.backend.update.install;

import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.update.ArtifactKind;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseVerificationException;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.SignedRelease;
import gg.modl.update.UpdateFiles;
import gg.modl.update.VerifiedCopy;
import java.io.IOException;
import java.nio.file.Files;

public final class VerifiedArtifacts {
    private VerifiedArtifacts() {
    }

    public static VerifiedCopy copy(UpdateFiles files, String relativeArtifact, ArtifactKind kind, ReleaseKeys keys) throws IOException {
        ReleaseVersion version = files.versionOf(relativeArtifact);
        SignedRelease release;
        try {
            release = SignedRelease.verify(Files.readAllBytes(files.envelope(version)), keys, JacksonJsonMaps.INSTANCE);
        } catch (ReleaseVerificationException e) {
            throw new IOException("The release file of modl " + version + " does not verify: " + e.getMessage(), e);
        }
        if (!release.version().equals(version)) {
            throw new IOException("The release file in " + files.versionDirectory(version) + " is for modl " + release.version());
        }
        return VerifiedCopy.of(files.resolve(relativeArtifact), release.manifest().artifact(kind), files.runDirectory());
    }
}
