package gg.modl.minecraft.core.impl.http;

import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.imports.ImportFileUpload;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class StreamedFileUpload implements ImportFileUpload {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = (int) Duration.ofSeconds(10).toMillis();
    private static final int RESPONSE_TIMEOUT_MILLIS = (int) Duration.ofMinutes(2).toMillis();
    private static final long STALL_NANOS = Duration.ofSeconds(60).toNanos();

    private final BackendConnections connections;
    private volatile @Nullable HttpURLConnection connection;
    private volatile long lastWriteNanos;
    private volatile boolean writing;
    private volatile boolean aborted;

    public StreamedFileUpload(BackendConnections connections) {
        this.connections = connections;
    }

    @Override
    public int send(String jobId, Path file) throws IOException {
        long length = Files.size(file);
        HttpURLConnection request = connections.open(ModlHttpClientV2Impl.BASE_PATH + ModlHttpClientV2Impl.IMPORT_JOBS_PATH
                + "/" + jobId + "/file");
        connection = request;
        try {
            request.setRequestMethod("PUT");
            request.setDoOutput(true);
            request.setInstanceFollowRedirects(false);
            request.setFixedLengthStreamingMode(length);
            request.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            request.setReadTimeout(RESPONSE_TIMEOUT_MILLIS);
            request.setRequestProperty(AbstractModlHttpTransport.HEADER_CONTENT_TYPE, "application/octet-stream");
            write(request, file);
            int status = request.getResponseCode();
            connections.reportIfRefused(request, status);
            return status;
        } finally {
            writing = false;
            request.disconnect();
            connection = null;
        }
    }

    private void write(HttpURLConnection request, Path file) throws IOException {
        requireNotAborted();
        lastWriteNanos = System.nanoTime();
        writing = true;
        try (InputStream source = Files.newInputStream(file); OutputStream body = request.getOutputStream()) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = source.read(buffer)) != -1) {
                requireNotAborted();
                body.write(buffer, 0, read);
                lastWriteNanos = System.nanoTime();
            }
        }
        writing = false;
    }

    private void requireNotAborted() throws IOException {
        if (aborted) throw new IOException("The import upload was stopped");
    }

    @Override
    public boolean isStalled() {
        return writing && System.nanoTime() - lastWriteNanos > STALL_NANOS;
    }

    @Override
    public void abort() {
        aborted = true;
        HttpURLConnection request = connection;
        if (request != null) request.disconnect();
    }
}
