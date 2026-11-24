package gg.modl.backend.hostedimport.intake;

import gg.modl.backend.hostedimport.archive.ArchiveLimits;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

@Slf4j
public final class LinkDownloader {
    private static final int MAX_ATTEMPTS = 5;
    private static final long CHUNK = 8L * 1024 * 1024;
    private static final Duration STALL = Duration.ofSeconds(60);
    private static final int OK = 200;
    private static final int PARTIAL = 206;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;

    private final ArchiveTransport transport;
    private final ImportArchiveFiles files;

    public LinkDownloader(ArchiveTransport transport, ImportArchiveFiles files) {
        this.transport = transport;
        this.files = files;
    }

    public long download(String importId, URI link, BooleanSupplier stopRequested, TransferProgress progress) {
        files.create(importId);
        long received = 0;
        long total = -1;
        String etag = null;
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "modl-hosted-import-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    ArchiveTransport.Response response = transport.get(link, received, etag);
                    total = acceptedTotal(response, received, total);
                    if (files.shortfall(total - received) > 0) {
                        close(response.body());
                        throw new ImportFailure(ImportReason.DISK_SPACE);
                    }
                    if (etag == null) {
                        etag = response.etag().orElse(null);
                    }
                    received = copy(importId, response.body(), received, total, watchdog, stopRequested, progress);
                    if (received == total) {
                        return total;
                    }
                } catch (IOException | UncheckedIOException e) {
                    if (stopRequested.getAsBoolean()) {
                        throw new ImportFailure(ImportReason.CANCELLED, e);
                    }
                    if (attempt >= MAX_ATTEMPTS) {
                        throw new ImportFailure(ImportReason.LINK_UNREACHABLE, e);
                    }
                    log.debug("Retrying the archive download after a transfer error");
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw new ImportFailure(ImportReason.LINK_UNREACHABLE);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImportFailure(ImportReason.CANCELLED, e);
        } finally {
            watchdog.shutdownNow();
        }
    }

    private static long acceptedTotal(ArchiveTransport.Response response, long received, long knownTotal) throws IOException {
        int status = response.status();
        if (status >= 300 && status < 400) {
            close(response.body());
            throw new ImportFailure(ImportReason.LINK_REDIRECTED);
        }
        if (status == FORBIDDEN || status == NOT_FOUND) {
            close(response.body());
            throw new ImportFailure(ImportReason.LINK_EXPIRED);
        }
        boolean expected = received == 0 ? status == OK : status == PARTIAL;
        if (!expected || response.contentLength().isEmpty()) {
            close(response.body());
            throw new ImportFailure(ImportReason.LINK_BAD_RESPONSE);
        }
        long total = received + response.contentLength().getAsLong();
        if (total <= 0 || total > ArchiveLimits.MAX_ARCHIVE_BYTES || (knownTotal >= 0 && total != knownTotal)) {
            close(response.body());
            throw new ImportFailure(total > ArchiveLimits.MAX_ARCHIVE_BYTES ? ImportReason.ARCHIVE_TOO_LARGE : ImportReason.LINK_BAD_RESPONSE);
        }
        return total;
    }

    private long copy(String importId, InputStream body, long received, long total, ScheduledExecutorService watchdog,
                      BooleanSupplier stopRequested, TransferProgress progress) throws IOException {
        AtomicLong lastProgress = new AtomicLong(System.nanoTime());
        ScheduledFuture<?> stall = watchdog.scheduleAtFixedRate(() -> {
            if (stopRequested.getAsBoolean() || System.nanoTime() - lastProgress.get() > STALL.toNanos()) {
                close(body);
            }
        }, 1, 1, TimeUnit.SECONDS);
        long position = received;
        try (InputStream stream = body) {
            while (position < total) {
                if (stopRequested.getAsBoolean()) {
                    throw new ImportFailure(ImportReason.CANCELLED);
                }
                long chunk = Math.min(CHUNK, total - position);
                if (files.shortfall(chunk) > 0) {
                    throw new ImportFailure(ImportReason.DISK_SPACE);
                }
                long written = files.append(importId, position, new ProgressStream(stream, lastProgress), chunk);
                if (written == 0) {
                    break;
                }
                position += written;
                progress.received(position, total);
            }
            return position;
        } finally {
            stall.cancel(false);
        }
    }

    private static void close(@Nullable InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException e) {
            log.debug("Could not close the download body", e);
        }
    }

    @FunctionalInterface
    public interface TransferProgress {
        void received(long receivedBytes, long totalBytes);
    }

    private static final class ProgressStream extends InputStream {
        private final InputStream source;
        private final AtomicLong lastProgress;

        private ProgressStream(InputStream source, AtomicLong lastProgress) {
            this.source = source;
            this.lastProgress = lastProgress;
        }

        @Override
        public int read() throws IOException {
            int value = source.read();
            lastProgress.set(System.nanoTime());
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = source.read(buffer, offset, length);
            lastProgress.set(System.nanoTime());
            return read;
        }
    }
}
