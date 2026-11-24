package gg.modl.backend.hostedimport.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.hostedimport.archive.ArchiveLimits;
import gg.modl.backend.hostedimport.intake.ImportArchiveFiles;
import gg.modl.backend.hostedimport.intake.LinkDownloader;
import gg.modl.backend.hostedimport.intake.LinkPolicy;
import gg.modl.backend.hostedimport.job.ArchiveSession;
import gg.modl.backend.hostedimport.job.ImportApplier;
import gg.modl.backend.hostedimport.job.ImportCompletion;
import gg.modl.backend.hostedimport.job.ImportEligibility;
import gg.modl.backend.hostedimport.job.ImportNotices;
import gg.modl.backend.hostedimport.job.ImportOutcomes;
import gg.modl.backend.hostedimport.job.ImportPlan;
import gg.modl.backend.hostedimport.job.ImportPlanFactory;
import gg.modl.backend.hostedimport.job.ImportStates;
import gg.modl.backend.hostedimport.job.ImportVerifier;
import gg.modl.backend.hostedimport.job.ImportWorker;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.mapping.RetentionPlanner;
import gg.modl.backend.hostedimport.mapping.StaffPlan;
import gg.modl.backend.hostedimport.model.ImportChoices;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportSource;
import gg.modl.backend.hostedimport.model.ImportStatus;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.hostedimport.model.RetentionChoice;
import gg.modl.backend.hostedimport.model.RetentionPlan;
import gg.modl.backend.hostedimport.repository.HostedImportStateRepository;
import gg.modl.backend.instance.setup.SetupBlocker;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.migration.service.ConcurrentImport;
import gg.modl.backend.migration.service.ImportKind;
import gg.modl.backend.migration.service.ImportExclusion;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.settings.service.DataRetentionSettingsService;
import gg.modl.backend.settings.service.ReplayRetentionSettingsService;
import gg.modl.backend.storage.service.StorageOwnership;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HostedImportService implements SetupBlocker, ConcurrentImport {
    public static final String IMPORT_ACTIVE = "IMPORT_ACTIVE";
    public static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;
    private static final Duration PROGRESS_EVERY = Duration.ofSeconds(2);
    private static final int STAFF_PAGE = 5_000;

    private final ImportStates states;
    private final HostedImportStateRepository stateRepository;
    private final ImportEligibility eligibility;
    private final ImportArchiveFiles files;
    private final LinkPolicy linkPolicy;
    private final LinkDownloader downloader;
    private final ImportWorker worker;
    private final ImportVerifier verifier;
    private final ImportApplier applier;
    private final ImportOutcomes outcomes;
    private final ImportCompletion completion;
    private final ImportNotices notices;
    private final ArchiveSession session;
    private final ImportPlanFactory planFactory;
    private final ImportExclusion exclusion;
    private final StorageOwnership storageOwnership;
    private final DataRetentionSettingsService dataRetention;
    private final ReplayRetentionSettingsService replayRetention;
    private final PanelActionAuditor auditor;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final ReentrantLock receiving = new ReentrantLock();

    public ImportStateView state(Server server) {
        Optional<ImportJob> job = states.current();
        List<String> blockers = job.filter(current -> current.status().isInProgress()).isPresent()
            ? List.of()
            : eligibility.blockers(server, job);
        return new ImportStateView(job, blockers.isEmpty(), blockers, MAX_CHUNK_BYTES);
    }

    public ImportJob beginUpload(Server server, ImportRequests.BeginUpload request) {
        if (request.sizeBytes() <= 0 || request.sizeBytes() > ArchiveLimits.MAX_ARCHIVE_BYTES) {
            throw new HostedImportException(HttpStatus.CONTENT_TOO_LARGE, "IMPORT_TOO_LARGE", "The archive is too large");
        }
        if (files.shortfall(request.sizeBytes()) > 0) {
            throw HostedImportException.diskSpace();
        }
        return begin(server, ImportSource.UPLOAD, request.fileName(), request.sizeBytes(), request.expectedSha256());
    }

    public ImportJob startDownload(Server server, ImportRequests.StartDownload request) {
        URI link = linkPolicy.check(request.url()).orElseThrow(() -> new HostedImportException(HttpStatus.BAD_REQUEST,
            "IMPORT_LINK_NOT_ALLOWED", "Paste the download link from the modl.gg export email"));
        ImportJob job = begin(server, ImportSource.LINK, null, 0, request.expectedSha256());
        worker.submit(() -> download(job.id(), link));
        return job;
    }

    private ImportJob begin(Server server, ImportSource source, @Nullable String fileName, long size, @Nullable String expectedSha256) {
        ImportJob created = exclusion.exclusively(() -> unitOfWork.write(() -> {
            Optional<ImportJob> current = stateRepository.find().map(HostedImportStateRepository.StoredJob::job);
            if (current.filter(job -> job.status().isInProgress()).isPresent()) {
                throw HostedImportException.inProgress();
            }
            if (!eligibility.blockers(server, current).isEmpty()) {
                throw HostedImportException.notAvailable();
            }
            stateRepository.deleteAll();
            ImportJob job = ImportJob.started(UUID.randomUUID().toString(), source, fileName, size,
                expectedSha256 == null ? null : expectedSha256.toLowerCase(Locale.ROOT), clock.instant());
            stateRepository.insert(job);
            unitOfWork.afterCommit(() -> {
                current.ifPresent(previous -> files.delete(previous.id()));
                session.forget();
            });
            return job;
        }));
        files.create(created.id());
        return created;
    }

    private void download(String importId, URI link) {
        BooleanSupplier stop = worker.stopSignal(importId);
        try {
            AtomicReference<Instant> reported = new AtomicReference<>(Instant.EPOCH);
            long total = downloader.download(importId, link, stop, (received, expected) -> {
                Instant now = clock.instant();
                if (Duration.between(reported.get(), now).compareTo(PROGRESS_EVERY) >= 0) {
                    reported.set(now);
                    states.transition(importId, ImportStatus.DOWNLOADING,
                        job -> job.withReceivedBytes(received).withSizeBytes(expected).withUpdatedAt(now));
                }
            });
            if (states.transition(importId, ImportStatus.DOWNLOADING, job -> job.withReceivedBytes(total).withSizeBytes(total)
                .withStatus(ImportStatus.VERIFYING).withUpdatedAt(clock.instant())).isPresent()) {
                verifier.verify(importId);
            }
        } catch (RuntimeException e) {
            outcomes.failed(importId, ImportStatus.DOWNLOADING, e, stop);
        }
    }

    public ImportJob receiveChunk(String importId, long offset, long length, InputStream body) {
        if (length < 0) {
            throw new HostedImportException(HttpStatus.LENGTH_REQUIRED, "IMPORT_LENGTH_REQUIRED", "The chunk needs a Content-Length");
        }
        if (length > MAX_CHUNK_BYTES) {
            throw new HostedImportException(HttpStatus.CONTENT_TOO_LARGE, "IMPORT_CHUNK_TOO_LARGE", "The chunk is too large");
        }
        if (!receiving.tryLock()) {
            throw HostedImportException.inProgress();
        }
        try {
            ImportJob job = requireJob(importId, ImportStatus.RECEIVING);
            if (offset != job.receivedBytes() || offset + length > job.sizeBytes() || length == 0) {
                throw new HostedImportException(HttpStatus.CONFLICT, "IMPORT_UPLOAD_OFFSET",
                    "The upload continues at byte " + job.receivedBytes());
            }
            if (files.shortfall(length) > 0) {
                throw HostedImportException.diskSpace();
            }
            long written = files.append(importId, offset, body, length);
            if (written != length) {
                throw new HostedImportException(HttpStatus.BAD_REQUEST, "IMPORT_UPLOAD_OFFSET", "The chunk ended early");
            }
            return states.transition(importId, ImportStatus.RECEIVING,
                    current -> current.withReceivedBytes(offset + length).withUpdatedAt(clock.instant()))
                .orElseThrow(HostedImportException::wrongState);
        } finally {
            receiving.unlock();
        }
    }

    public ImportJob completeUpload(String importId) {
        ImportJob job = requireJob(importId, ImportStatus.RECEIVING);
        if (job.receivedBytes() != job.sizeBytes()) {
            throw new HostedImportException(HttpStatus.CONFLICT, "IMPORT_UPLOAD_INCOMPLETE", "The upload is not complete");
        }
        ImportJob verifying = states.transition(importId, ImportStatus.RECEIVING,
                current -> current.withStatus(ImportStatus.VERIFYING).withUpdatedAt(clock.instant()))
            .orElseThrow(HostedImportException::wrongState);
        worker.submit(() -> verifier.verify(importId));
        return verifying;
    }

    public ImportPreview preview(Server server) {
        ReviewedArchive reviewed = reviewed(server);
        StaffPlan staff = reviewed.plan().staff();
        Optional<StaffPlan.Candidate> archivedOwner = staff.archivedOwner();
        String archivedAdmin = reviewed.index().server().adminEmail();
        return new ImportPreview(
            reviewed.job(),
            reviewed.job().tally().plus(reviewed.issues().tally()),
            retentionPlans(server, reviewed),
            archivedAdmin,
            Objects.requireNonNullElse(server.getAdminEmail(), ""),
            staff.ownerEmailsMatch(archivedAdmin),
            archivedOwner.map(StaffPlan.Candidate::minecraftUsername).orElse(null),
            archivedOwner.isPresent(),
            files.availableBytes(),
            staff.candidates());
    }

    private ReviewedArchive reviewed(Server server) {
        ImportJob job = states.current().filter(current -> current.status() == ImportStatus.REVIEW)
            .orElseThrow(HostedImportException::wrongState);
        ArchiveIndex index = session.indexFor(job.id()).orElseThrow(HostedImportException::wrongState);
        IssueLog issues = new IssueLog();
        ImportPlan plan = planFactory.plan(server, index, issues, clock.instant(), null, stateRepository.aliases(), null);
        return new ReviewedArchive(job, index, plan, issues);
    }

    public List<StaffPlan.Candidate> staffPage(Server server, int offset) {
        List<StaffPlan.Candidate> candidates = reviewed(server).plan().staff().candidates();
        int from = Math.clamp(offset, 0, candidates.size());
        return candidates.subList(from, Math.min(candidates.size(), from + STAFF_PAGE));
    }

    public static int staffPageSize() {
        return STAFF_PAGE;
    }

    private List<RetentionPlan> retentionPlans(Server server, ReviewedArchive reviewed) {
        long fixed = verifier.fixedBytes(reviewed.index().manifest());
        return RetentionPlanner.plans(reviewed.index().retention(), dataRetention.getDataRetentionSettings(server),
                replayRetention.getReplayRetentionSettings(server), reviewed.plan().settings().archivedReplayRetention()).stream()
            .map(retention -> retention.withRequiredBytes(fixed + retention.estimatedBytes()))
            .toList();
    }

    public ImportJob start(Server server, ImportRequests.Start request) {
        return exclusion.exclusively(() -> {
            ImportJob job = requireJob(request.importId(), ImportStatus.REVIEW);
            if (!eligibility.dataBlockers(server).isEmpty()) {
                throw HostedImportException.notAvailable();
            }
            ReviewedArchive reviewed = reviewed(server);
            if (reviewed.plan().staff().selectionProblem(request.staffIds(), request.mergeArchivedOwner()).isPresent()) {
                throw new HostedImportException(HttpStatus.CONFLICT, "IMPORT_STAFF_SELECTION", "A selected staff member cannot be restored");
            }
            if (!storageOwnership.verify().isOwned()) {
                throw new HostedImportException(HttpStatus.CONFLICT, "IMPORT_STORAGE_NOT_OWNED", "The file storage belongs to another installation");
            }
            RetentionPlan retention = retentionPlans(server, reviewed).stream()
                .filter(candidate -> candidate.choice() == request.retention())
                .findFirst()
                .orElseThrow(HostedImportException::wrongState);
            if (files.shortfall(retention.requiredBytes()) > 0) {
                throw HostedImportException.diskSpace();
            }
            Instant now = clock.instant();
            ImportJob applying = unitOfWork.write(() -> {
                stateRepository.saveChoices(new ImportChoices(request.staffIds(), request.mergeArchivedOwner(), retention));
                return states.transitionInUnit(job.id(), ImportStatus.REVIEW, current -> current
                        .withStatus(ImportStatus.APPLYING)
                        .withAcceptedAt(now)
                        .withTally(current.tally().carriedIntoApply())
                        .enteringPhase(ImportPhase.BEGIN, now)
                        .withPhaseTotal(1))
                    .orElseThrow(HostedImportException::wrongState);
            });
            notices.started();
            worker.submit(() -> applier.apply(job.id(), false));
            return applying;
        });
    }

    public ImportJob resume(String importId) {
        ImportJob job = requireJob(importId, ImportStatus.PAUSED);
        if (!job.canResume()) {
            throw HostedImportException.wrongState();
        }
        ImportJob applying = states.transition(importId, ImportStatus.PAUSED,
                current -> current.withStatus(ImportStatus.APPLYING).withReason(null).withUpdatedAt(clock.instant()))
            .orElseThrow(HostedImportException::wrongState);
        worker.submit(() -> applier.apply(importId, false));
        return applying;
    }

    public void discard(String importId) {
        ImportJob job = states.current().filter(current -> current.id().equals(importId))
            .orElseThrow(HostedImportException::notFound);
        if (!job.canDiscard()) {
            throw HostedImportException.wrongState();
        }
        worker.cancel(importId);
        unitOfWork.write(() -> {
            boolean discardable = stateRepository.find()
                .filter(stored -> stored.job().id().equals(importId) && stored.job().canDiscard())
                .isPresent();
            if (!discardable) {
                throw HostedImportException.wrongState();
            }
            stateRepository.deleteAll();
            unitOfWork.afterCommit(() -> {
                files.delete(importId);
                session.forget();
            });
        });
    }

    public ImportJob abandon(Server server, String importId) {
        ImportJob job = requireJob(importId, ImportStatus.PAUSED);
        if (!job.canAbandon()) {
            throw HostedImportException.wrongState();
        }
        return unitOfWork.write(() -> {
            ImportJob abandoned = states.transitionInUnit(importId, ImportStatus.PAUSED, current -> current
                    .withStatus(ImportStatus.ABANDONED).withFinishedAt(clock.instant()).withUpdatedAt(clock.instant()))
                .orElseThrow(HostedImportException::wrongState);
            auditor.recordStaffAction(server, server.getAdminEmail(), "Abandoned the import from modl.gg before staff access was restored");
            unitOfWork.afterCommit(() -> {
                files.delete(importId);
                session.forget();
            });
            return abandoned;
        });
    }

    public ImportJob finish(Server server, String importId) {
        ImportJob job = requireJob(importId, ImportStatus.PAUSED);
        if (!job.canFinish()) {
            throw HostedImportException.wrongState();
        }
        return completion.complete(server, importId, ImportStatus.PAUSED, current -> current.finishedEarly(clock.instant()),
                "Finished the import from modl.gg export " + exportId(job) + " with the data imported so far")
            .orElseThrow(HostedImportException::wrongState);
    }

    private static String exportId(ImportJob job) {
        return job.archive() == null ? job.id() : job.archive().exportId();
    }

    public ImportJob report() {
        return states.current().filter(job -> job.archive() != null && job.status().hasWrittenData())
            .orElseThrow(HostedImportException::notFound);
    }

    public Optional<RetentionChoice> reportedRetention() {
        return stateRepository.choices().map(choices -> choices.retention().choice());
    }

    @Override
    public Optional<String> blockingCompletion() {
        return activeCode();
    }

    @Override
    public Optional<String> blockingStep(SetupStep step) {
        return step == SetupStep.IMPORT ? activeCode() : Optional.empty();
    }

    @Override
    public ImportKind kind() {
        return ImportKind.HOSTED;
    }

    @Override
    public boolean isInProgress() {
        return states.current().filter(job -> job.status().isInProgress()).isPresent();
    }

    private Optional<String> activeCode() {
        return isInProgress() ? Optional.of(IMPORT_ACTIVE) : Optional.empty();
    }

    private ImportJob requireJob(String importId, ImportStatus status) {
        ImportJob job = states.current().filter(current -> current.id().equals(importId))
            .orElseThrow(HostedImportException::notFound);
        if (job.status() != status) {
            throw HostedImportException.wrongState();
        }
        return job;
    }

    private record ReviewedArchive(ImportJob job, ArchiveIndex index, ImportPlan plan, IssueLog issues) {
    }
}
