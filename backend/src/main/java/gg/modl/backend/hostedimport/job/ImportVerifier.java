package gg.modl.backend.hostedimport.job;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.hostedimport.archive.ArchiveManifest;
import gg.modl.backend.hostedimport.archive.HostedArchive;
import gg.modl.backend.hostedimport.archive.ManifestReader;
import gg.modl.backend.hostedimport.archive.VerifiedEntryStream;
import gg.modl.backend.hostedimport.intake.ImportArchiveFiles;
import gg.modl.backend.hostedimport.mapping.ArchiveAnalyzer;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.model.ArchiveSummary;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportStatus;
import gg.modl.backend.player.config.IpHistoryPolicy;
import gg.modl.backend.storage.StorageLocation;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImportVerifier {
    private static final Duration PROGRESS_EVERY = Duration.ofSeconds(2);
    private static final double STORED_ROW_FACTOR = 2.5;
    private static final long SQLITE_WAL_BYTES = 64L * 1024 * 1024;

    private final ImportStates states;
    private final ImportArchiveFiles files;
    private final ArchiveSession session;
    private final ImportOutcomes outcomes;
    private final ImportWorker worker;
    private final IpHistoryPolicy ipHistory;
    private final StorageLocation storageLocation;
    private final DatabaseDialect dialect;
    private final Clock clock;

    public void verify(String importId) {
        BooleanSupplier stop = worker.stopSignal(importId);
        try {
            states.current(importId, ImportStatus.VERIFYING).ifPresent(job -> verify(job, stop));
        } catch (RuntimeException e) {
            outcomes.failed(importId, ImportStatus.VERIFYING, e, stop);
        }
    }

    private void verify(ImportJob job, BooleanSupplier stop) {
        if (!files.exists(job.id()) || files.size(job.id()) != job.sizeBytes()) {
            throw new ImportFailure(ImportReason.ARCHIVE_INCOMPLETE);
        }
        String sha256 = files.sha256(job.id(), stop);
        if (job.expectedSha256() != null && !job.expectedSha256().equalsIgnoreCase(sha256)) {
            throw new ImportFailure(ImportReason.ARCHIVE_EXPECTED_HASH);
        }
        try (HostedArchive archive = HostedArchive.open(files.pathOf(job.id()))) {
            ArchiveManifest manifest = manifest(archive);
            long total = manifest.collectionBytes() + manifest.storageBytes();
            Progress progress = new Progress(job.id(), total);
            ArchiveIndex index = new ArchiveAnalyzer(archive, manifest, clock.instant(), ipHistory.ipHistoryDays(), stop)
                .analyse(true, progress::report);
            if (stop.getAsBoolean()) {
                return;
            }
            session.remember(job.id(), index);
            states.transition(job.id(), ImportStatus.VERIFYING, current -> current
                .withStatus(ImportStatus.REVIEW)
                .withPhase(ImportPhase.VERIFY)
                .withArchiveSha256(sha256)
                .withArchive(summary(manifest))
                .withTally(index.analysisTally())
                .withRequiredBytes(requiredBytes(manifest))
                .progressed(total, total, clock.instant()));
        }
    }

    static ArchiveManifest manifest(HostedArchive archive) {
        try (VerifiedEntryStream stream = archive.openManifest()) {
            ArchiveManifest manifest = ManifestReader.read(stream);
            stream.drainAndVerify();
            return manifest;
        } catch (IOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED, e);
        }
    }

    long requiredBytes(ArchiveManifest manifest) {
        return fixedBytes(manifest) + (long) (STORED_ROW_FACTOR * manifest.collectionBytes());
    }

    public long fixedBytes(ArchiveManifest manifest) {
        long storage = storageLocation.writesToLocalDisk() ? manifest.storageBytes() : 0;
        return storage + (dialect == DatabaseDialect.SQLITE ? SQLITE_WAL_BYTES : 0);
    }

    private static ArchiveSummary summary(ArchiveManifest manifest) {
        return new ArchiveSummary(
            manifest.exportId(),
            manifest.source().backendVersion(),
            manifest.createdAt(),
            manifest.source().serverName(),
            manifest.source().subdomain(),
            manifest.excludedCollections().stream()
                .map(excluded -> new ArchiveSummary.NamedCount(excluded.name(), excluded.reason(), excluded.approximateDocuments()))
                .toList(),
            manifest.skippedObjects().stream()
                .collect(Collectors.groupingBy(ArchiveManifest.SkippedObject::reason, TreeMap::new, Collectors.counting()))
                .entrySet().stream()
                .map(entry -> new ArchiveSummary.NamedCount(entry.getKey(), entry.getKey(), entry.getValue()))
                .toList());
    }

    private final class Progress {
        private final String importId;
        private final long total;
        private Instant lastReport = Instant.EPOCH;

        private Progress(String importId, long total) {
            this.importId = importId;
            this.total = total;
        }

        void report(long done) {
            Instant now = clock.instant();
            if (Duration.between(lastReport, now).compareTo(PROGRESS_EVERY) < 0) {
                return;
            }
            lastReport = now;
            states.transition(importId, ImportStatus.VERIFYING, job -> job.progressed(done, total, now));
        }
    }
}
