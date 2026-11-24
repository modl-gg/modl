package gg.modl.backend.update.install;

import gg.modl.update.ArtifactKind;
import gg.modl.update.HostDescriptor;
import gg.modl.update.SignedRelease;
import gg.modl.update.TransactionKind;
import java.io.IOException;
import java.util.Optional;

public interface UpdateHost {
    ArtifactKind artifactKind();

    TransactionKind transactionKind();

    boolean sameLineOnly();

    int hostApi(SignedRelease release);

    Optional<HostDescriptor> descriptor();

    Optional<String> runningArtifact();

    void prepare(SignedRelease release) throws IOException;

    void restart();
}
