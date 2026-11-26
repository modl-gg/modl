package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.response.ImportJob;
import gg.modl.minecraft.core.imports.source.ImportProblem;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

@RequiredArgsConstructor
final class ImportUploader {
    static final int MAX_FAILED_ATTEMPTS = 5;

    private static final int FIRST_SUCCESS = 200, FIRST_REDIRECT = 300, CONTENT_TOO_LARGE = 413, NO_RESPONSE = -1;

    private final ImportBackend backend;
    private final ImportCalls calls;
    private final ImportSignal signal;
    private final ImportTimings timings;
    private volatile @Nullable ImportFileUpload sending;

    void upload(String jobId, Path file) {
        int failedAttempts = 0;
        while (true) {
            if (signal.isSet()) throw ImportFailure.cancelled();
            String transferError;
            int status;
            try {
                status = send(jobId, file);
                transferError = "HTTP " + status;
            } catch (IOException broken) {
                status = NO_RESPONSE;
                transferError = broken.toString();
            }
            if (status >= FIRST_SUCCESS && status < FIRST_REDIRECT) return;

            ImportJob job = calls.call(api -> api.getImportJob(jobId), signal, timings.getGiveUpAfter().toMillis()).getJob();
            if (ImportJobs.isProcessing(job) || ImportJobs.isCompleted(job)) return;
            if (ImportJobs.isEnded(job)) throw ImportFailure.cancelled();
            if (!ImportJobs.RECEIVING.equals(job.getStatus())) {
                if (status == CONTENT_TOO_LARGE) {
                    throw new ImportFailure(ImportEnding.UPLOAD_FAILED, ImportProblem.UPLOAD_BLOCKED, transferError);
                }
                if (++failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    throw new ImportFailure(ImportEnding.UPLOAD_FAILED, ImportProblem.UPLOAD_FAILED, transferError);
                }
            }
            signal.await(timings.getRetryEvery().toMillis());
        }
    }

    private int send(String jobId, Path file) throws IOException {
        ImportFileUpload upload = backend.newUpload();
        sending = upload;
        try {
            if (signal.isSet()) throw ImportFailure.cancelled();
            return upload.send(jobId, file);
        } finally {
            sending = null;
        }
    }

    void abort() {
        ImportFileUpload upload = sending;
        if (upload != null) upload.abort();
    }

    void abortIfStalled() {
        ImportFileUpload upload = sending;
        if (upload != null && upload.isStalled()) upload.abort();
    }
}
