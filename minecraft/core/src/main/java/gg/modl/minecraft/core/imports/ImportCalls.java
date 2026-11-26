package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.ImportApi;
import gg.modl.minecraft.core.imports.source.ImportProblem;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

@RequiredArgsConstructor
final class ImportCalls {
    private static final long RESPONSE_WAIT_SECONDS = 30;
    private static final int REQUEST_TIMEOUT = 408, TOO_MANY_REQUESTS = 429;

    private final ImportBackend backend;
    private final long retryDelayMillis;

    <T> T once(Function<ImportApi, CompletableFuture<T>> request, ImportSignal signal) {
        return call(request, signal, 0);
    }

    <T> T call(Function<ImportApi, CompletableFuture<T>> request, ImportSignal signal, long giveUpAfterMillis) {
        long startedAt = System.nanoTime();
        while (true) {
            if (signal.isSet()) throw ImportFailure.cancelled();
            Throwable failure;
            try {
                return request.apply(backend.api()).get(RESPONSE_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw ImportFailure.cancelled();
            } catch (ExecutionException failed) {
                failure = unwrap(failed);
            } catch (TimeoutException | RuntimeException failed) {
                failure = unwrap(failed);
            }
            if (isFinalAnswer(failure)) throw (ApiClientException) failure;
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) >= giveUpAfterMillis) {
                throw new ImportFailure(ImportEnding.UPLOAD_FAILED, ImportProblem.UNREACHABLE, failure);
            }
            signal.await(retryDelayMillis);
        }
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        Throwable cause = current.getCause();
        while ((current instanceof CompletionException || current instanceof ExecutionException) && cause != null) {
            current = cause;
            cause = current.getCause();
        }
        return current;
    }

    private static boolean isFinalAnswer(Throwable failure) {
        if (!(failure instanceof ApiClientException)) return false;
        int status = ((ApiClientException) failure).getStatusCode();
        return status != REQUEST_TIMEOUT && status != TOO_MANY_REQUESTS;
    }
}
