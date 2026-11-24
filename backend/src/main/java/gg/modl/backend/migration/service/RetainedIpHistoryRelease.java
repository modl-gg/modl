package gg.modl.backend.migration.service;

import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.IpHistoryRetention;
import gg.modl.backend.migration.repository.ImportJobRepository;
import gg.modl.backend.player.repository.RetainedIpHistoryRepository;
import gg.modl.backend.retention.RetentionBatches;
import gg.modl.backend.retention.service.RetentionTask;
import gg.modl.backend.server.data.Server;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetainedIpHistoryRelease implements RetentionTask {
    private final ImportJobRepository importJobRepository;
    private final RetainedIpHistoryRepository retainedIpHistory;

    @Override
    public String subject() {
        return "imported IP history holds";
    }

    @Override
    public long purge(Server server, Instant now) {
        return releasePass();
    }

    long releaseWhile(BooleanSupplier keepGoing) {
        long released = 0;
        while (keepGoing.getAsBoolean()) {
            long pass = releasePass();
            released += pass;
            if (pass == 0) {
                break;
            }
        }
        return released;
    }

    boolean isPending() {
        return latestImportEndedHolds() && retainedIpHistory.anyRetained();
    }

    Optional<Instant> keptBefore() {
        return latestImportEndedHolds() ? Optional.empty() : retainedIpHistory.latestRetainedSighting();
    }

    private synchronized long releasePass() {
        return latestImportEndedHolds()
            ? RetentionBatches.drain(RetentionBatches.DEFAULT_BATCH_SIZE, RetentionBatches.DEFAULT_MAX_BATCHES_PER_RUN,
                retainedIpHistory::releaseBatch)
            : 0;
    }

    private boolean latestImportEndedHolds() {
        return importJobRepository.findLatest()
            .filter(job -> job.getStatus() == ImportState.COMPLETED && job.getIpHistory() == IpHistoryRetention.STANDARD)
            .isPresent();
    }
}
