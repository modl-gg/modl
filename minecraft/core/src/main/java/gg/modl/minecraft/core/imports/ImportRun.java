package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.request.ExportProgressRequest;
import gg.modl.minecraft.api.http.request.StartImportJobRequest;
import gg.modl.minecraft.api.http.response.ImportJob;
import gg.modl.minecraft.api.http.response.ImportJobResponse;
import gg.modl.minecraft.api.http.response.ImportStatusResponse;
import gg.modl.minecraft.core.imports.source.ImportProblem;
import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.util.Futures;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

final class ImportRun {
    private static final Map<String, String> REFUSAL_MESSAGES = mapOf(
            "IP_HISTORY_RELEASE_RUNNING", "import.ip_history_release_running",
            "IMPORT_STORAGE_MOVING", "import.storage_moving",
            "IMPORT_DATABASE_MOVING", "import.database_moving");
    private static final int ANNOUNCE_PERCENT_STEP = 10;

    private final ImportRuntime runtime;
    private final ImportSource source;
    private final ScopedPunishmentChoice scopedChoice;
    private final IpHistoryChoice historyChoice;
    private final ImportSignal signal;
    private final ImportUploader uploader;
    private final ExportProgress progress = new ExportProgress();
    private final AtomicBoolean reporting = new AtomicBoolean();
    private volatile @Nullable SourceReader reader;
    private volatile boolean accepted;
    private final PreviewedReader previewed;
    private int announcedPercent;
    private long announcedAt;

    ImportRun(ImportRuntime runtime, ImportSource source, ScopedPunishmentChoice scopedChoice,
              IpHistoryChoice historyChoice, PreviewedReader previewed) {
        this.runtime = runtime;
        this.previewed = previewed;
        this.source = source;
        this.scopedChoice = scopedChoice;
        this.historyChoice = historyChoice;
        this.signal = new ImportSignal(runtime.getLifecycle(), this::interruptWorkLater);
        this.uploader = new ImportUploader(runtime.getBackend(), runtime.getCalls(), signal, runtime.getTimings());
    }

    boolean cancelBeforeAcceptance() {
        if (accepted) return false;
        signal.set();
        return true;
    }

    void stop() {
        signal.set();
    }

    String sourceName() {
        return source.displayName();
    }

    void execute() {
        SourceReader opened = readerFor(previewed.take(source.id()));
        try {
            publish(opened);
            SourceInventory inventory = inventoryOf(opened);
            if (inventory.isEmpty()) {
                runtime.getReporter().say(source, "import.nothing_to_import");
                return;
            }
            ImportJobResponse started = start();
            if (started != null) runJob(started.getJob(), started.getMaxUploadBytes(), opened, inventory);
        } finally {
            reader = null;
            opened.close();
        }
    }

    private SourceReader readerFor(@Nullable SourceReader kept) {
        if (kept == null) return ImportSourceAccess.open(source);
        if (kept.isReusable()) return kept;
        try {
            if (signal.isSet()) throw ImportFailure.cancelled();
            runtime.getLogger().debug("The " + source.displayName() + " connection kept from the preview cannot be reused, opening it again");
            return ImportSourceAccess.reopen(source);
        } finally {
            kept.close();
        }
    }

    private void publish(SourceReader opened) {
        reader = opened;
        if (signal.isSet()) throw ImportFailure.cancelled();
    }

    private SourceInventory inventoryOf(SourceReader opened) {
        try {
            return opened.inventory();
        } catch (SourceException | RuntimeException failure) {
            if (signal.isSet()) throw ImportFailure.cancelled();
            throw ImportFailure.fromSource(failure);
        }
    }

    private @Nullable ImportJobResponse start() {
        StartImportJobRequest request = new StartImportJobRequest(source.id(), runtime.getServerName().get(),
                scopedChoice.getWireName(), historyChoice.getWireName());
        try {
            return runtime.getCalls().once(api -> api.startImport(request), signal);
        } catch (ApiClientException refused) {
            explainRefusedStart(refused);
            return null;
        }
    }

    private void explainRefusedStart(ApiClientException refused) {
        String refusal = refused.getCode() == null ? null : REFUSAL_MESSAGES.get(refused.getCode());
        if (refusal != null) {
            runtime.getReporter().say(source, refusal);
            return;
        }
        ImportStatusResponse current = runtime.getCalls().once(api -> api.getImportStatus(), signal);
        if (ImportJobs.isActive(current.getJob())) runtime.getReporter().alreadyRunning(current.getJob());
        else if (!current.isSetupReady()) runtime.getReporter().say(source, "import.setup_required");
        else runtime.getReporter().failed(source, ImportProblem.REQUEST_FAILED.messageKey(), refused.getMessage());
    }

    private void runJob(ImportJob job, long maxBytes, SourceReader opened, SourceInventory inventory) {
        String jobId = job.getId();
        try {
            runtime.getFiles().begin(jobId);
        } catch (IOException failure) {
            fail(jobId, new ImportFailure(ImportEnding.EXPORT_FAILED, ImportProblem.FILE_FAILED, failure));
            return;
        }
        runtime.getReporter().say(source, "import.started", mapOf(
                "scoped_choice", scopedChoice.getWord(), "history_choice", historyChoice.getWord()));
        announcedAt = System.nanoTime();
        ScheduledFuture<?> reports = null;
        try {
            long every = runtime.getTimings().getReportEvery().toMillis();
            reports = runtime.getMonitor().scheduleWithFixedDelay(() -> watch(jobId), every, every, TimeUnit.MILLISECONDS);
            Path export = runtime.getFiles().export(jobId);
            new ImportExporter(source, opened, signal, runtime.getLogger())
                    .export(export, inventory, scopedChoice, maxBytes, progress,
                            Instant.ofEpochMilli(runtime.getClock().getAsLong()));
            beginUpload(jobId);
            uploader.upload(jobId, export);
            accepted = true;
        } catch (ImportFailure failure) {
            fail(jobId, failure);
            return;
        } catch (RuntimeException | LinkageError failure) {
            runtime.getLogger().warning("The " + source.displayName() + " import failed", failure);
            ImportEnding ending = progress.isUploading() ? ImportEnding.UPLOAD_FAILED : ImportEnding.EXPORT_FAILED;
            fail(jobId, new ImportFailure(ending, ImportProblem.REQUEST_FAILED, failure));
            return;
        } finally {
            if (reports != null) reports.cancel(false);
        }
        runtime.getFiles().discardExport(jobId);
        runtime.getReporter().say(source, "import.processing_started");
        runtime.getFollower().follow(jobId);
    }

    private void beginUpload(String jobId) {
        progress.uploadStarts();
        ExportProgressRequest request = progress.toRequest();
        ImportJob job = runtime.getCalls().call(api -> api.reportExportProgress(jobId, request), signal,
                runtime.getTimings().getGiveUpAfter().toMillis()).getJob();
        if (ImportJobs.isEnded(job)) throw ImportFailure.cancelled();
        runtime.getReporter().say(source, "import.uploading");
    }

    private void fail(String jobId, ImportFailure failure) {
        if (runtime.getLifecycle().isSet()) {
            runtime.getFiles().discardExport(jobId);
            return;
        }
        if (signal.isSet() || failure.getEnding() == ImportEnding.CANCELLED) {
            runtime.getFollower().end(jobId, ImportEnding.CANCELLED, null);
            return;
        }
        runtime.getReporter().failed(source, failure.getMessageKey(), failure.getMessage());
        runtime.getFollower().end(jobId, failure.getEnding(), failure.getMessage());
    }

    private void watch(String jobId) {
        try {
            uploader.abortIfStalled();
            announceExport();
            report(jobId);
        } catch (RuntimeException | LinkageError failure) {
            reporting.set(false);
            runtime.getLogger().debug(source.displayName() + " import report failed: " + failure);
        }
    }

    private void announceExport() {
        if (progress.isUploading()) return;
        int percent = progress.percent();
        long now = System.nanoTime();
        if (percent < announcedPercent + ANNOUNCE_PERCENT_STEP
                || now - announcedAt < runtime.getTimings().getAnnounceEvery().toNanos()) {
            return;
        }
        announcedPercent = percent;
        announcedAt = now;
        runtime.getReporter().say(source, "import.exporting", mapOf(
                "processed", String.valueOf(progress.handled()), "total", String.valueOf(progress.total())));
    }

    private void report(String jobId) {
        if (signal.isSet() || !reporting.compareAndSet(false, true)) return;
        CompletableFuture<ImportJobResponse> sent =
                runtime.getBackend().api().reportExportProgress(jobId, progress.toRequest());
        Futures.whenComplete(sent, (response, failure) -> {
            reporting.set(false);
            boolean ended = failure == null ? ImportJobs.isEnded(response.getJob()) : isGone(failure);
            if (ended) signal.set();
        }, runtime.getLogger());
    }

    private static boolean isGone(Throwable failure) {
        Throwable cause = ImportCalls.unwrap(failure);
        return cause instanceof ApiClientException
                && ((ApiClientException) cause).getStatusCode() == HttpURLConnection.HTTP_NOT_FOUND;
    }

    private void interruptWorkLater() {
        try {
            runtime.getMonitor().execute(this::interruptWork);
        } catch (RejectedExecutionException stopped) {
            runtime.getLogger().debug("The import monitor has already stopped");
        }
    }

    private void interruptWork() {
        uploader.abort();
        SourceReader running = reader;
        if (running == null) return;
        try {
            running.cancelRunning();
        } catch (RuntimeException failure) {
            runtime.getLogger().debug("The " + source.displayName() + " query could not be cancelled: " + failure);
        }
    }
}
