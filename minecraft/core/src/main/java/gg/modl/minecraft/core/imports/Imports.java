package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.ImportApi;
import gg.modl.minecraft.api.http.request.EndImportJobRequest;
import gg.modl.minecraft.api.http.response.ImportJob;
import gg.modl.minecraft.core.imports.source.ImportProblem;
import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.Java8Collections;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Imports {
    private final ImportRuntime runtime;
    private final ImportReporter reporter;
    private final ImportConfirmation confirmation = new ImportConfirmation(System::nanoTime);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("modl-import"));
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile @Nullable ImportRun current;
    private final AtomicReference<@Nullable SourceReader> previewing = new AtomicReference<>();
    private final PreviewedReader previewed;

    @Builder
    private Imports(ImportSources sources, ImportBackend backend, LocaleManager localeManager,
                    Consumer<String> console, PluginLogger logger, Path dataFolder, Supplier<String> serverName) {
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(daemon("modl-import-monitor"));
        ImportTimings timings = ImportTimings.DEFAULT;
        ImportSignal lifecycle = new ImportSignal();
        ImportCalls calls = new ImportCalls(backend, timings.getRetryEvery().toMillis());
        ImportFiles files = new ImportFiles(dataFolder, logger);
        this.reporter = new ImportReporter(localeManager, console, sources);
        this.previewed = new PreviewedReader(monitor, logger);
        this.runtime = ImportRuntime.builder()
                .sources(sources)
                .backend(backend)
                .calls(calls)
                .files(files)
                .reporter(reporter)
                .follower(new ImportJobFollower(calls, files, reporter, lifecycle, timings))
                .monitor(monitor)
                .lifecycle(lifecycle)
                .timings(timings)
                .logger(logger)
                .serverName(serverName)
                .warningText(() -> localeManager.getMessage("config.default_reason"))
                .clock(System::currentTimeMillis)
                .build();
    }

    public static Path scratchFolder(Path dataFolder) {
        return ImportFiles.scratchFolder(dataFolder);
    }

    public static Path nativeFolder(Path dataFolder) {
        return ImportFiles.nativeFolder(dataFolder);
    }

    public List<String> detectedSources() {
        return runtime.getSources().detectedIds();
    }

    public void usage() {
        reporter.say("import.usage");
        try {
            runtime.getMonitor().execute(() -> reporter.supported(runtime.getSources().detect()));
        } catch (RejectedExecutionException stopped) {
            runtime.getLogger().debug("The import monitor has already stopped");
        }
    }

    public void preview(String sourceId) {
        ImportSource source = knownSource(sourceId);
        if (source == null) return;
        Slot slot = claimSlot();
        if (slot == null) return;
        confirmation.disarm();
        previewed.discardLater();
        inBackground(() -> {
            runtime.getSources().detect();
            new ImportPreview(runtime, source, previewing, previewed).show(() -> {
                confirmation.arm(source.id());
                slot.release();
            });
        }, source, slot);
    }

    public void confirm(String sourceId, @Nullable String scopedWord, @Nullable String historyWord) {
        Optional<ScopedPunishmentChoice> scoped = ScopedPunishmentChoice.parse(scopedWord);
        Optional<IpHistoryChoice> history = IpHistoryChoice.parse(historyWord);
        ImportSource source = knownSource(sourceId);
        if (source == null) return;
        if (!scoped.isPresent() || !history.isPresent()) {
            reporter.say(source, "import.choices_required");
            return;
        }
        if (!confirmation.isArmed(source.id())) {
            reporter.say(source, "import.confirm_first");
            return;
        }
        Slot slot = claimSlot();
        if (slot == null) return;
        if (!confirmation.consume(source.id())) {
            slot.release();
            reporter.say(source, "import.confirm_first");
            return;
        }
        ImportRun run = new ImportRun(runtime, source, scoped.get(), history.get(), previewed);
        current = run;
        if (!inBackground(() -> {
            try {
                run.execute();
            } finally {
                current = null;
            }
        }, source, slot)) {
            current = null;
        }
    }

    public void status() {
        request(ImportApi::getImportStatus).thenAccept(reporter::status).exceptionally(this::requestFailed);
    }

    public void cancel() {
        stopPreviewLater();
        ImportRun local = current;
        boolean cancelledHere = local != null && local.cancelBeforeAcceptance();
        if (cancelledHere) reporter.say("import.cancelling");
        request(ImportApi::getImportStatus)
                .thenAccept(response -> cancelActive(response.getJob(), cancelledHere))
                .exceptionally(this::requestFailed);
    }

    private void cancelActive(ImportJob job, boolean cancelledHere) {
        if (!ImportJobs.isActive(job)) {
            if (!cancelledHere) reporter.say("import.nothing_to_cancel");
            return;
        }
        EndImportJobRequest request = new EndImportJobRequest(ImportEnding.CANCELLED.wireName(), null);
        request(api -> api.endImport(job.getId(), request)).thenAccept(ended -> {
            if (!cancelledHere) reporter.result(ended.getJob());
            reporter.say("import.cancel_keeps_imported");
        }).exceptionally(this::requestFailed);
    }

    public void resumeInterrupted() {
        if (runtime.getLifecycle().isSet() || !busy.compareAndSet(false, true)) return;
        inBackground(() -> {
            runtime.getSources().detect();
            for (String jobId : runtime.getFiles().discardExportsAndListInterruptedJobs()) {
                runtime.getFollower().resume(jobId);
            }
        }, null, new Slot());
    }

    public void shutdown() {
        runtime.getLifecycle().set();
        stopPreviewLater();
        previewed.discardLater();
        ImportRun running = current;
        if (running != null) running.stop();
        worker.shutdown();
        runtime.getMonitor().shutdown();
    }

    private void stopPreviewLater() {
        if (previewing.get() == null) return;
        try {
            runtime.getMonitor().execute(this::stopPreview);
        } catch (RejectedExecutionException stopped) {
            runtime.getLogger().debug("The import monitor has already stopped");
        }
    }

    private void stopPreview() {
        SourceReader reader = previewing.get();
        if (reader == null) return;
        try {
            reader.cancelRunning();
        } catch (RuntimeException failure) {
            runtime.getLogger().debug("The import preview query could not be cancelled: " + failure);
        }
    }

    private @Nullable ImportSource knownSource(String sourceId) {
        ImportSource source = runtime.getSources().byId(sourceId);
        if (source == null) {
            reporter.unknownSource(sourceId);
            return null;
        }
        return source;
    }

    private @Nullable Slot claimSlot() {
        if (runtime.getLifecycle().isSet()) return null;
        if (busy.compareAndSet(false, true)) return new Slot();
        reporter.say("import.busy");
        return null;
    }

    private boolean inBackground(Runnable task, @Nullable ImportSource source, Slot slot) {
        try {
            worker.execute(() -> runReporting(task, source, slot));
            return true;
        } catch (RejectedExecutionException stopped) {
            slot.release();
            runtime.getLogger().debug("The import worker has already stopped");
            return false;
        }
    }

    private void runReporting(Runnable task, @Nullable ImportSource source, Slot slot) {
        try {
            if (!runtime.getLifecycle().isSet()) task.run();
        } catch (ImportFailure failure) {
            if (runtime.getLifecycle().isSet()) return;
            if (failure.getCause() != null) runtime.getLogger().warning("The import failed", failure.getCause());
            reportFailure(source, failure.getMessageKey(), failure.getMessage());
        } catch (ApiClientException refused) {
            reportFailure(source, ImportProblem.REQUEST_FAILED.messageKey(), refused.getMessage());
        } catch (RuntimeException | LinkageError failure) {
            if (runtime.getLifecycle().isSet()) return;
            runtime.getLogger().warning("The import failed", failure);
            reportFailure(source, ImportProblem.REQUEST_FAILED.messageKey(), failure.toString());
        } finally {
            slot.release();
        }
    }

    private void reportFailure(@Nullable ImportSource source, String key, @Nullable String detail) {
        if (source != null) reporter.failed(source, key, detail);
        else reporter.failed(key, detail);
    }

    private <T> CompletableFuture<T> request(Function<ImportApi, CompletableFuture<T>> call) {
        try {
            return call.apply(runtime.getBackend().api());
        } catch (RuntimeException unavailable) {
            return Java8Collections.failedFuture(unavailable);
        }
    }

    private Void requestFailed(Throwable failure) {
        reporter.failed(ImportProblem.REQUEST_FAILED.messageKey(), ImportCalls.unwrap(failure).getMessage());
        return null;
    }

    private static ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class Slot {
        private final AtomicBoolean held = new AtomicBoolean(true);

        private void release() {
            if (held.compareAndSet(true, false)) busy.set(false);
        }
    }
}
