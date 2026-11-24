package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.ImportApi;
import gg.modl.minecraft.api.http.request.EndImportJobRequest;
import gg.modl.minecraft.api.http.request.ExportProgressRequest;
import gg.modl.minecraft.api.http.request.StartImportJobRequest;
import gg.modl.minecraft.api.http.response.ImportJob;
import gg.modl.minecraft.api.http.response.ImportJobResponse;
import gg.modl.minecraft.api.http.response.ImportStatusResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

final class FakeImportBackend implements ImportBackend, ImportApi {
    static final String JOB = "0123456789abcdef";
    static final int CONFLICT = 409;

    private static final int ACCEPTED = 202;
    private static final long MAX_BYTES = 1024 * 1024;
    private static final int IP_HISTORY_DAYS = 365;

    private final List<StartImportJobRequest> starts = new CopyOnWriteArrayList<>();
    private final List<EndImportJobRequest> ends = new CopyOnWriteArrayList<>();
    private final List<byte[]> uploads = new CopyOnWriteArrayList<>();
    private volatile String status;
    private volatile String statusAfterAttempt;
    private volatile String endReason;
    private volatile int answer = ACCEPTED;
    private volatile ApiClientException startRefusal;

    FakeImportBackend withJob(String jobStatus) {
        status = jobStatus;
        return this;
    }

    FakeImportBackend answering(int httpStatus, String jobStatusAfterwards) {
        answer = httpStatus;
        statusAfterAttempt = jobStatusAfterwards;
        return this;
    }

    FakeImportBackend refusingStartsWith(String code) {
        startRefusal = new ApiClientException(CONFLICT, "refused", code);
        return this;
    }

    List<StartImportJobRequest> starts() {
        return starts;
    }

    List<EndImportJobRequest> ends() {
        return ends;
    }

    List<byte[]> uploads() {
        return uploads;
    }

    @Override
    public ImportApi api() {
        return this;
    }

    @Override
    public ImportFileUpload newUpload() {
        return new ImportFileUpload() {
            @Override
            public int send(String jobId, Path file) throws IOException {
                uploads.add(Files.readAllBytes(file));
                if (statusAfterAttempt != null) {
                    status = statusAfterAttempt;
                } else if (answer == ACCEPTED) {
                    status = ImportJobs.COMPLETED;
                }
                return answer;
            }

            @Override
            public boolean isStalled() {
                return false;
            }

            @Override
            public void abort() {
            }
        };
    }

    @Override
    public CompletableFuture<ImportJobResponse> startImport(StartImportJobRequest request) {
        starts.add(request);
        if (startRefusal != null) {
            CompletableFuture<ImportJobResponse> refused = new CompletableFuture<>();
            refused.completeExceptionally(startRefusal);
            return refused;
        }
        status = ImportJobs.BUILDING;
        return response();
    }

    @Override
    public CompletableFuture<ImportStatusResponse> getImportStatus() {
        ImportJob job = status == null ? null : job();
        return CompletableFuture.completedFuture(new ImportStatusResponse(job, true, MAX_BYTES, IP_HISTORY_DAYS, null));
    }

    @Override
    public CompletableFuture<ImportJobResponse> getImportJob(String jobId) {
        return response();
    }

    @Override
    public CompletableFuture<ImportJobResponse> reportExportProgress(String jobId, ExportProgressRequest request) {
        if (ImportJobs.BUILDING.equals(status) && request.getPhase().endsWith("UPLOADING")) status = ImportJobs.UPLOADING;
        return response();
    }

    @Override
    public CompletableFuture<ImportJobResponse> endImport(String jobId, EndImportJobRequest request) {
        ends.add(request);
        if (!ImportJobs.PROCESSING.equals(status) && !ImportJobs.COMPLETED.equals(status)) {
            status = ImportJobs.FAILED;
            endReason = request.getReason();
        }
        return response();
    }

    private CompletableFuture<ImportJobResponse> response() {
        return CompletableFuture.completedFuture(new ImportJobResponse(job(), MAX_BYTES));
    }

    private ImportJob job() {
        return ImportJob.builder().id(JOB).status(status).endReason(endReason).build();
    }
}
