package com.alessiodp.libby.download;

import com.alessiodp.libby.LibbyProperties;
import com.alessiodp.libby.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;

import static java.util.Objects.requireNonNull;

public final class ArtifactDownloader {
    public static final long UNKNOWN_SIZE = -1;
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 5000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String PARTIAL_SUFFIX = ".part";
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final int HTTP_REDIRECTION = 300;
    private static final int HTTP_CLIENT_ERROR = 400;

    private final Logger logger;
    private final RetryPolicy retryPolicy;

    public ArtifactDownloader(@NotNull Logger logger) {
        this(logger, RetryPolicy.DEFAULT);
    }

    public ArtifactDownloader(@NotNull Logger logger, @NotNull RetryPolicy retryPolicy) {
        this.logger = requireNonNull(logger, "logger");
        this.retryPolicy = requireNonNull(retryPolicy, "retryPolicy");
    }

    public void download(@NotNull String artifact, @NotNull Collection<String> urls, byte @Nullable [] checksum,
                         long expectedSize, @NotNull Path target, @NotNull TransferListener listener) throws IOException {
        IOException notFound = new IOException("no download location");
        IOException failed = null;
        for (String url : urls) {
            int failedAttempts = 0;
            while (true) {
                requireNotCancelled();
                try {
                    fetch(artifact, url, checksum, expectedSize, target, listener);
                    return;
                } catch (FileNotFoundException missing) {
                    logger.debug("File not found: " + url);
                    notFound = new IOException("not found at " + url, missing);
                    break;
                } catch (LocalFileException local) {
                    requireNotCancelled();
                    throw local;
                } catch (IntegrityException | UnusableResponseException corrupt) {
                    requireNotCancelled();
                    if (failed == null) failed = corrupt;
                    break;
                } catch (IOException networkFailure) {
                    requireNotCancelled();
                    failedAttempts++;
                    if (failed == null) failed = networkFailure;
                    logger.debug("Attempt " + failedAttempts + " to download " + url + " failed: " + describe(networkFailure));
                    if (!retryPolicy.allowsAnotherAttempt(failedAttempts)) break;
                    retryPolicy.pauseAfter(failedAttempts);
                }
            }
        }
        IOException reported = failed != null ? failed : notFound;
        throw new IOException(describe(reported), reported);
    }

    public static byte @NotNull [] sha256(@NotNull Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    private void fetch(String artifact, String url, byte[] checksum, long expectedSize, Path target,
                       TransferListener listener) throws IOException {
        listener.transferred(0);
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", LibbyProperties.HTTP_USER_AGENT);

        Path directory = target.toAbsolutePath().getParent();
        Path partial = local(directory, () -> {
            Files.createDirectories(directory);
            return Files.createTempFile(directory, target.getFileName() + ".", PARTIAL_SUFFIX);
        });
        try {
            requireNoRedirect(connection, url);
            byte[] actual = copy(connection, url, expectedSize, partial, listener);
            if (checksum != null && !Arrays.equals(actual, checksum)) {
                logInvalidChecksum(artifact, url, checksum, actual);
                throw new IntegrityException("the download from " + url + " does not match its pinned SHA-256");
            }
            local(directory, () -> moveIntoPlace(partial, target));
            logger.info("Downloaded " + artifact + " from " + url);
        } finally {
            deleteQuietly(partial);
        }
    }

    private static void requireNoRedirect(URLConnection connection, String url) throws IOException {
        if (!(connection instanceof HttpURLConnection)) return;
        int status = ((HttpURLConnection) connection).getResponseCode();
        if (status < HTTP_REDIRECTION || status >= HTTP_CLIENT_ERROR) return;
        String location = connection.getHeaderField("Location");
        throw new UnusableResponseException("the download from " + url + " answered HTTP " + status
                + (location == null ? "" : ", a redirect to " + location + " that is not followed between http and https; use that address"));
    }

    private void deleteQuietly(Path partial) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException inUse) {
            logger.debug("Could not remove the partial download " + partial + ": " + describe(inUse));
        }
    }

    private static byte[] copy(URLConnection connection, String url, long expectedSize, Path partial,
                               TransferListener listener) throws IOException {
        MessageDigest digest = newDigest();
        Path directory = partial.getParent();
        try (InputStream input = connection.getInputStream();
             OutputStream output = local(directory, () -> Files.newOutputStream(partial))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long transferred = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                requireNotCancelled();
                transferred += read;
                if (expectedSize != UNKNOWN_SIZE && transferred > expectedSize) {
                    throw new IntegrityException("the download from " + url + " is larger than its expected "
                            + expectedSize + " bytes");
                }
                digest.update(buffer, 0, read);
                int length = read;
                local(directory, () -> {
                    output.write(buffer, 0, length);
                    return null;
                });
                listener.transferred(transferred);
            }
        }
        return digest.digest();
    }

    private static <T> T local(Path directory, LocalOperation<T> operation) throws LocalFileException {
        try {
            return operation.run();
        } catch (IOException failure) {
            throw new LocalFileException("could not write to " + directory + " (" + describe(failure) + ")", failure);
        }
    }

    private static Path moveIntoPlace(Path partial, Path target) throws IOException {
        try {
            return Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            return Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void logInvalidChecksum(String artifact, String url, byte[] expected, byte[] actual) {
        logger.warn("*** INVALID CHECKSUM ***");
        logger.warn(" Library :  " + artifact);
        logger.warn(" URL :  " + url);
        logger.warn(" Expected :  " + Base64.getEncoder().encodeToString(expected));
        logger.warn(" Actual :  " + Base64.getEncoder().encodeToString(actual));
    }

    private static void requireNotCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
    }

    private static String describe(IOException failure) {
        if (failure instanceof UnknownHostException) return "unknown host " + failure.getMessage();
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", unavailable);
        }
    }

    @FunctionalInterface
    private interface LocalOperation<T> {
        T run() throws IOException;
    }

    private static final class IntegrityException extends IOException {
        private IntegrityException(String message) {
            super(message);
        }
    }

    private static final class UnusableResponseException extends IOException {
        private UnusableResponseException(String message) {
            super(message);
        }
    }
}
