package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.Repositories;
import com.alessiodp.libby.download.ArtifactDownloader;
import com.alessiodp.libby.download.LocalFileException;
import com.alessiodp.libby.logging.Logger;
import gg.modl.minecraft.core.util.Hex;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

final class MavenCentralDownloader implements ServerLibraryDownloader {
    static final int PARALLEL_DOWNLOADS = 6;
    private static final long POLL_MILLIS = 200;
    private static final long WORKER_STOP_SECONDS = 15;
    private static final String THREAD_NAME = "modl-library-download-";

    private final ArtifactDownloader downloader;
    private final String repository;
    private final int parallelDownloads;

    MavenCentralDownloader(ArtifactDownloader downloader, String repository, int parallelDownloads) {
        this.downloader = downloader;
        this.repository = repository.endsWith("/") ? repository : repository + "/";
        this.parallelDownloads = parallelDownloads;
    }

    static MavenCentralDownloader create(PluginLogger logger) {
        return new MavenCentralDownloader(new ArtifactDownloader(new Logger(new DebugLogAdapter(logger))),
                Repositories.MAVEN_CENTRAL, PARALLEL_DOWNLOADS);
    }

    @Override
    public void download(List<CentralLibrary> libraries, Path directory, DownloadListener listener,
                         BooleanSupplier cancelled) {
        if (libraries.isEmpty()) return;
        requireNotCancelled(cancelled);
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(parallelDownloads, libraries.size()), daemonThreads());
        CompletionService<CentralLibrary> completions = new ExecutorCompletionService<>(workers);
        try {
            for (CentralLibrary library : libraries) completions.submit(() -> fetch(library, directory, listener));
            int finished = 0;
            while (finished < libraries.size()) {
                requireNotCancelled(cancelled);
                Future<CentralLibrary> done = completions.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (done == null) continue;
                listener.completed(downloaded(done, directory).getFile());
                finished++;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while downloading the built-in panel libraries");
        } finally {
            stop(workers);
        }
    }

    private CentralLibrary fetch(CentralLibrary library, Path directory, DownloadListener listener)
            throws LibraryDownloadException {
        MavenArtifact artifact = library.getArtifact();
        ServerLibrary file = library.getFile();
        try {
            downloader.download(artifact.toString(), Collections.singletonList(repository + artifact.repositoryPath()),
                    Hex.decode(file.getSha256()), file.getSize(), directory.resolve(file.getFileName()),
                    listener.listenerFor(file));
        } catch (IOException failure) {
            throw new LibraryDownloadException(artifact, failure);
        }
        return library;
    }

    private CentralLibrary downloaded(Future<CentralLibrary> done, Path directory) throws InterruptedException {
        try {
            return done.get();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            throw new EmbeddedServerException(failureMessage(cause, directory), cause);
        }
    }

    private String failureMessage(@Nullable Throwable cause, Path directory) {
        Throwable local = cause == null ? null : cause.getCause();
        if (cause instanceof LibraryDownloadException && local instanceof LocalFileException) {
            return "The built-in panel could not save its libraries (" + local.getMessage() + "). Free disk "
                    + "space or fix the permissions of " + directory + "; modl tries again on the next start.";
        }
        String failure = cause instanceof LibraryDownloadException
                ? cause.getMessage()
                : "its libraries (" + cause + ")";
        return "The built-in panel could not download " + failure + ". This server needs outbound HTTPS access to "
                + URI.create(repository).getHost() + "; modl tries the download again on the next start.";
    }

    private static void requireNotCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("The built-in panel start was cancelled");
    }

    private static void stop(ExecutorService workers) {
        workers.shutdownNow();
        try {
            workers.awaitTermination(WORKER_STOP_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger threads = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    static final class LibraryDownloadException extends Exception {
        LibraryDownloadException(MavenArtifact artifact, IOException failure) {
            super(artifact + " from Maven Central (" + failure.getMessage() + ")", failure);
        }
    }
}
