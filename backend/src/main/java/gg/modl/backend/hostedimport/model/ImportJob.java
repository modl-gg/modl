package gg.modl.backend.hostedimport.model;

import java.time.Instant;
import java.util.EnumSet;
import lombok.With;
import org.jspecify.annotations.Nullable;

@With
public record ImportJob(
    String id,
    ImportStatus status,
    ImportSource source,
    ImportPhase phase,
    long cursor,
    @Nullable ImportReason reason,
    @Nullable String fileName,
    long sizeBytes,
    long receivedBytes,
    @Nullable String expectedSha256,
    @Nullable String archiveSha256,
    long requiredBytes,
    long phaseDone,
    long phaseTotal,
    @Nullable ArchiveSummary archive,
    ImportTally tally,
    @Nullable Instant acceptedAt,
    @Nullable Instant finishedAt,
    Instant updatedAt
) {
    public static ImportJob started(String id, ImportSource source, @Nullable String fileName, long sizeBytes,
                                    @Nullable String expectedSha256, Instant now) {
        ImportStatus status = source == ImportSource.UPLOAD ? ImportStatus.RECEIVING : ImportStatus.DOWNLOADING;
        return new ImportJob(id, status, source, ImportPhase.VERIFY, 0, null, fileName, sizeBytes, 0, expectedSha256, null, 0,
            0, 0, null, ImportTally.EMPTY, null, null, now);
    }

    public Instant requireAcceptedAt() {
        if (acceptedAt == null) {
            throw new IllegalStateException("Import " + id + " is applying without an acceptance time");
        }
        return acceptedAt;
    }

    public boolean canDiscard() {
        return status.isDiscardable();
    }

    public boolean canResume() {
        return status == ImportStatus.PAUSED && reason != ImportReason.ARCHIVE_MISSING;
    }

    public boolean canAbandon() {
        return status == ImportStatus.PAUSED && !phase.grantsAccessFromHere();
    }

    public boolean canFinish() {
        return status == ImportStatus.PAUSED && phase.grantsAccessFromHere();
    }

    public ImportJob completed(ImportTally applied, Instant now) {
        return withTally(tally.plus(applied)).withStatus(ImportStatus.COMPLETED).withFinishedAt(now).withUpdatedAt(now);
    }

    public ImportJob finishedEarly(Instant now) {
        IssueLog unfinished = new IssueLog();
        for (ImportPhase remaining : EnumSet.range(phase, ImportPhase.FINISH)) {
            boolean done = remaining == phase && phaseDone >= phaseTotal;
            if (!done) {
                remaining.unfinishedIssue().ifPresent(code -> unfinished.report(code, 1));
            }
        }
        return completed(unfinished.tally(), now);
    }

    public ImportJob failed(ImportReason failure, Instant now) {
        return withStatus(ImportStatus.FAILED).withReason(failure).withUpdatedAt(now);
    }

    public ImportJob paused(ImportReason failure, Instant now) {
        return withStatus(ImportStatus.PAUSED).withReason(failure).withUpdatedAt(now);
    }

    public ImportJob progressed(long done, long total, Instant now) {
        return withPhaseDone(done).withPhaseTotal(total).withUpdatedAt(now);
    }

    public ImportJob enteringPhase(ImportPhase next, Instant now) {
        return withPhase(next).withCursor(0).withPhaseDone(0).withPhaseTotal(0).withUpdatedAt(now);
    }
}
