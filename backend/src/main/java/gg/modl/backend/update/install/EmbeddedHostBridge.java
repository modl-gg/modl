package gg.modl.backend.update.install;

import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.guard.UpdatePhaseReporter;
import gg.modl.update.ArtifactKind;
import gg.modl.update.HostDescriptor;
import gg.modl.update.SignedRelease;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdateFiles;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class EmbeddedHostBridge implements UpdateHost, UpdatePhaseReporter {
    static final long PREPARE_LIMIT_MINUTES = 30;

    private final EmbeddedUpdateInputs inputs;
    private final UpdateFiles files;
    private final ReleaseBuild build;

    public EmbeddedHostBridge(EmbeddedUpdateInputs inputs, UpdateFiles files, ReleaseBuild build) {
        this.inputs = inputs;
        this.files = files;
        this.build = build;
    }

    @Override
    public ArtifactKind artifactKind() {
        return ArtifactKind.EMBEDDED;
    }

    @Override
    public TransactionKind transactionKind() {
        return TransactionKind.EMBEDDED;
    }

    @Override
    public boolean sameLineOnly() {
        return true;
    }

    @Override
    public int hostApi(SignedRelease release) {
        return release.manifest().embeddedHostApi();
    }

    @Override
    public Optional<HostDescriptor> descriptor() {
        return Optional.of(inputs.host());
    }

    @Override
    public Optional<String> runningArtifact() {
        return RunningArtifact.of(files, build, inputs.host(), ArtifactKind.EMBEDDED);
    }

    @Override
    public void prepare(SignedRelease release) throws IOException {
        try {
            inputs.prepare().apply(release.version().toString()).toCompletableFuture().get(PREPARE_LIMIT_MINUTES, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            Throwable failure = cause != null ? cause : e;
            throw new IOException("The plugin could not prepare modl " + release.version() + ": " + failure.getMessage(), e);
        } catch (TimeoutException e) {
            throw new IOException("The plugin did not prepare modl " + release.version() + " within " + PREPARE_LIMIT_MINUTES
                + " minutes", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while the plugin prepared modl " + release.version(), e);
        }
    }

    @Override
    public void restart() {
        inputs.restart().run();
    }

    @Override
    public void report(String phase) {
        inputs.phase().accept(phase);
    }
}
