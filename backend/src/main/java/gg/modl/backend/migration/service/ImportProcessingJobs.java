package gg.modl.backend.migration.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.log.service.LogService;
import gg.modl.backend.migration.data.ImportEnd;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportSource;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.ImportedCounts;
import gg.modl.backend.migration.data.IpHistoryRetention;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.repository.ImportJobRepository;
import gg.modl.backend.player.repository.ImportedAddressRetention;
import gg.modl.backend.player.repository.RetainedIpHistoryRepository;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportProcessingJobs {
    private final ImportJobRepository importJobRepository;
    private final RetainedIpHistoryRepository retainedIpHistory;
    private final RetainedIpHistoryReleaseWorker releaseWorker;
    private final LogService logService;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private volatile boolean shuttingDown;

    @EventListener(ContextClosedEvent.class)
    public void stopForShutdown() {
        shuttingDown = true;
    }

    public boolean shouldContinue(String jobId) {
        return !shuttingDown && statusOf(jobId).filter(ImportState.PROCESSING::equals).isPresent();
    }

    public ImportedAddressRetention addressRetention(String jobId) {
        return ipHistoryOf(jobId).filter(IpHistoryRetention.KEEP::equals).isPresent()
            ? ImportedAddressRetention.KEPT
            : ImportedAddressRetention.STANDARD;
    }

    public boolean hasEnded(String jobId) {
        return statusOf(jobId).filter(ImportState::isActive).isEmpty();
    }

    public void reportProgress(String jobId, ImportedCounts counts) {
        unitOfWork.write(() -> {
            importJobRepository.recordProcessed(jobId, counts, clock.instant());
        });
    }

    public void complete(Server server, String jobId, ImportedCounts counts) {
        unitOfWork.write(() -> completeJob(server, jobId, counts));
        releaseEndedHolds();
    }

    private void releaseEndedHolds() {
        try {
            releaseWorker.releaseInBackground();
        } catch (TaskRejectedException busy) {
            log.warn("Releasing imported IP history waits for the next retention sweep", busy);
        }
    }

    private void completeJob(Server server, String jobId, ImportedCounts counts) {
        if (!importJobRepository.complete(jobId, counts, clock.instant())) {
            return;
        }
        Optional<MigrationStatus> job = importJobRepository.findById(jobId);
        boolean standard = job.map(MigrationStatus::getIpHistory).filter(IpHistoryRetention.STANDARD::equals).isPresent();
        if (standard && retainedIpHistory.anyRetained()) {
            String source = ImportSource.displayNameOf(job.map(MigrationStatus::getType).orElse(""));
            logService.recordStaffAction(server, MigrationService.AUDIT_SOURCE,
                source + " import completed with standard IP history: older imported IP history is no longer kept");
        }
    }

    public void fail(String jobId, ImportEnding ending, String detail, ImportedCounts counts) {
        unitOfWork.write(() -> {
            Instant now = clock.instant();
            if (importJobRepository.recordProcessed(jobId, counts, now)) {
                importJobRepository.end(jobId, Set.of(ImportState.PROCESSING), new ImportEnd(ending, detail, now));
            }
        });
    }

    private Optional<IpHistoryRetention> ipHistoryOf(String jobId) {
        return importJobRepository.findById(jobId).map(MigrationStatus::getIpHistory);
    }

    private Optional<ImportState> statusOf(String jobId) {
        return importJobRepository.findById(jobId).map(MigrationStatus::getStatus);
    }
}
