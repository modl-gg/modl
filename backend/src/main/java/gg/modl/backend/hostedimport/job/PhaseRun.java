package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.archive.HostedArchive;
import gg.modl.backend.hostedimport.intake.ImportArchiveFiles;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.mapping.ImportContext;
import gg.modl.backend.hostedimport.model.ImportChoices;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.server.data.Server;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.Builder;

@Builder
public record PhaseRun(
    ImportJob job,
    Server server,
    String ownerEmail,
    HostedArchive archive,
    ImportPlan plan,
    ImportChoices choices,
    RecordUnits units,
    ImportArchiveFiles files,
    BooleanSupplier stopRequested
) {
    private static final long DISK_STEP_BYTES = 64L * 1024 * 1024;

    long cursor() {
        return job.cursor();
    }

    ImportContext context() {
        return plan.context();
    }

    ArchiveIndex index() {
        return plan.context().index();
    }

    IssueLog issues() {
        return plan.context().issues();
    }

    void requireRunning() {
        if (stopRequested.getAsBoolean()) {
            throw new ImportFailure(ImportReason.CANCELLED);
        }
    }

    void requireDisk() {
        if (files.shortfall(DISK_STEP_BYTES) > 0) {
            throw new ImportFailure(ImportReason.DISK_SPACE);
        }
    }

    <T> void commit(List<Positioned<T>> batch, ImportSubject subject, Consumer<List<T>> write) {
        requireRunning();
        requireDisk();
        units.commit(job.id(), batch, subject, write, issues());
    }

    void commitState(long position, Runnable write) {
        requireRunning();
        requireDisk();
        units.commitState(job.id(), position, issues(), write);
    }
}
