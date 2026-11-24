package gg.modl.backend.migration.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.databasemove.DatabaseMoves;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.setup.SetupPhase;
import gg.modl.backend.log.service.AuditActor;
import gg.modl.backend.log.service.LogService;
import gg.modl.backend.migration.config.MigrationConfiguration;
import gg.modl.backend.migration.data.ExportCounts;
import gg.modl.backend.migration.data.ImportEnd;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportOrigin;
import gg.modl.backend.migration.data.ImportSource;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.IpHistoryRetention;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.data.ScopedPunishments;
import gg.modl.backend.migration.repository.ImportJobRepository;
import gg.modl.backend.player.config.IpHistoryPolicy;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.StorageMoves;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MigrationService {
    public static final String IMPORT_ACTIVE = "IMPORT_ACTIVE";
    public static final String SETUP_REQUIRED = "SETUP_REQUIRED";
    public static final String IP_HISTORY_RELEASE_RUNNING = "IP_HISTORY_RELEASE_RUNNING";
    public static final String STORAGE_MOVING = "IMPORT_STORAGE_MOVING";
    public static final String DATABASE_MOVING = "IMPORT_DATABASE_MOVING";

    static final AuditActor AUDIT_SOURCE = AuditActor.named("Console");
    static final String TIMED_OUT_MESSAGE = "The import stopped reporting and timed out";

    private final ImportJobRepository importJobRepository;
    private final RetainedIpHistoryRelease retainedIpHistoryRelease;
    private final IpHistoryPolicy ipHistoryProperties;
    private final MigrationConfiguration migrationConfiguration;
    private final LogService logService;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final ImportExclusion importExclusion;
    private final RunningImports runningImports;
    private final StorageMoves storageMoves;
    private final DatabaseMoves databaseMoves;

    public MigrationStatus start(Server server, String type, ImportOrigin origin, ScopedPunishments scopedPunishments,
                                 IpHistoryRetention ipHistory) {
        ImportSource source = ImportSource.fromId(type)
            .orElseThrow(() -> new ValidationException("Unknown import type"));
        return importExclusion.exclusively(() -> {
            requireSettledStorage();
            requireNoPendingRelease();
            return unitOfWork.write(() -> startJob(server, source, origin, scopedPunishments, ipHistory));
        });
    }

    private void requireSettledStorage() {
        if (databaseMoves.isMovingDatabase()) {
            throw new ConflictException("modl is moving to another database. Try again when the move finishes.", DATABASE_MOVING);
        }
        if (storageMoves.isMovingFiles()) {
            throw new ConflictException("Uploaded files are being moved to another storage. Try again when the move finishes.",
                STORAGE_MOVING);
        }
    }

    private void requireNoPendingRelease() {
        if (retainedIpHistoryRelease.isPending()) {
            throw new ConflictException("Imported IP history from an earlier import is still being released. Try again later.",
                IP_HISTORY_RELEASE_RUNNING);
        }
    }

    private MigrationStatus startJob(Server server, ImportSource source, ImportOrigin origin,
                                     ScopedPunishments scopedPunishments, IpHistoryRetention ipHistory) {
        Instant now = clock.instant();
        failStale(now);
        if (SetupPhase.of(server) != SetupPhase.READY) {
            throw new ConflictException("Finish the modl setup before importing", SETUP_REQUIRED);
        }
        if (importJobRepository.findActive().isPresent() || runningImports.anyBesides(ImportKind.PLUGIN)) {
            throw new ConflictException("An import is already running", IMPORT_ACTIVE);
        }
        MigrationStatus job = MigrationStatus.builder()
            .id(EntityIds.next())
            .type(source.id())
            .status(ImportState.BUILDING)
            .startedFrom(origin.serverName())
            .startedAddress(origin.address())
            .scopedPunishments(scopedPunishments)
            .ipHistory(ipHistory)
            .startedAt(now)
            .lastProgressAt(now)
            .build();
        importJobRepository.insert(job);
        logService.recordStaffAction(server, AUDIT_SOURCE, source.displayName() + " import started from the console of "
            + origin.serverName() + " (" + addressText(origin.address()) + "), IP-wide and server-scoped punishments: "
            + wordOf(scopedPunishments) + ", IP history: " + wordOf(ipHistory));
        return job;
    }

    public ImportOverview overview(Server server) {
        endStaleJob();
        return new ImportOverview(
            importJobRepository.findLatest(),
            SetupPhase.of(server) == SetupPhase.READY,
            migrationConfiguration.maxUploadBytes(),
            ipHistoryProperties.ipHistoryDays(),
            retainedIpHistoryRelease.keptBefore());
    }

    public MigrationStatus job(String jobId) {
        endStaleJob();
        return requireJob(jobId);
    }

    public MigrationStatus reportExport(String jobId, boolean uploading, ExportCounts counts) {
        requireJob(jobId);
        ImportState next = uploading ? ImportState.UPLOADING : ImportState.BUILDING;
        unitOfWork.write(() -> {
            Instant now = clock.instant();
            if (!importJobRepository.recordExport(jobId, next, counts, now)) {
                importJobRepository.refreshLease(jobId, ImportState.UPLOADING, now);
            }
        });
        return requireJob(jobId);
    }

    public MigrationStatus end(Server server, String jobId, ImportEnding ending, @Nullable String detail,
                               @Nullable String address) {
        if (!ending.mayBeReportedByPlugin()) {
            throw new ValidationException("The plugin cannot report this reason");
        }
        requireJob(jobId);
        Set<ImportState> endable = ending.endsAcceptedImport() ? ImportState.ACTIVE : ImportState.BEFORE_ACCEPTANCE;
        unitOfWork.write(() -> {
            boolean ended = importJobRepository.end(jobId, endable, new ImportEnd(ending, detail, clock.instant()));
            if (ended && ending == ImportEnding.CANCELLED) {
                logService.recordStaffAction(server, AUDIT_SOURCE, sourceNameOf(jobId)
                    + " import cancelled from the console at " + addressText(address));
            }
        });
        return requireJob(jobId);
    }

    private String sourceNameOf(String jobId) {
        return ImportSource.displayNameOf(requireJob(jobId).getType());
    }

    private MigrationStatus requireJob(String jobId) {
        return importJobRepository.findById(jobId)
            .orElseThrow(() -> new ResourceNotFoundException("No import has this id"));
    }

    private void endStaleJob() {
        Instant now = clock.instant();
        Optional<MigrationStatus> stale = importJobRepository.findActive().filter(job -> job.hasGoneStale(now));
        if (stale.isPresent()) {
            unitOfWork.write(() -> failStale(now));
        }
    }

    private void failStale(Instant now) {
        ImportEnd timedOut = new ImportEnd(ImportEnding.TIMED_OUT, TIMED_OUT_MESSAGE, now);
        for (ImportState state : ImportState.ACTIVE) {
            importJobRepository.failStale(state, state.staleBefore(now), timedOut);
        }
    }

    private static String addressText(@Nullable String address) {
        return address != null ? address : "unknown address";
    }

    private static String wordOf(Enum<?> choice) {
        return choice.name().toLowerCase(Locale.ROOT);
    }
}
