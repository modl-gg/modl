package gg.modl.backend.update.install;

import gg.modl.backend.update.guard.UpdatePhaseReporter;
import gg.modl.update.CommittedUpdate;
import gg.modl.update.UpdateTransaction;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Optional;

public final class UpdateHosts {
    @Nullable
    private final UpdateHost host;
    private final UpdatePhaseReporter reporter;

    UpdateHosts(@Nullable UpdateHost host, UpdatePhaseReporter reporter) {
        this.host = host;
        this.reporter = reporter;
    }

    public static UpdateHosts none() {
        return new UpdateHosts(null, UpdatePhaseReporter.NONE);
    }

    public Optional<UpdateHost> host() {
        return Optional.ofNullable(host);
    }

    public boolean runsDownloadedArtifact() {
        return host().flatMap(UpdateHost::runningArtifact).isPresent();
    }

    public CommittedUpdate committed(UpdateTransaction transaction, Instant committedAt) {
        return host()
            .flatMap(running -> running.runningArtifact().map(artifact -> new CommittedUpdate(transaction.id(),
                running.transactionKind(), transaction.to(), artifact, committedAt)))
            .orElseGet(() -> CommittedUpdate.of(transaction, committedAt));
    }

    public UpdatePhaseReporter reporter() {
        return reporter;
    }
}
