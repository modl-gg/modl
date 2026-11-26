package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.request.EndImportJobRequest;
import gg.modl.minecraft.api.http.response.ImportJob;
import gg.modl.minecraft.core.imports.source.ImportProblem;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.net.HttpURLConnection;

@RequiredArgsConstructor
final class ImportJobFollower {
    private static final int MAX_DETAIL_LENGTH = 1000;

    private final ImportCalls calls;
    private final ImportFiles files;
    private final ImportReporter reporter;
    private final ImportSignal lifecycle;
    private final ImportTimings timings;

    void resume(String jobId) {
        try {
            ImportJob job = calls.call(api -> api.getImportJob(jobId), lifecycle, timings.getGiveUpAfter().toMillis()).getJob();
            if (ImportJobs.isBeforeAcceptance(job)) {
                end(jobId, ImportEnding.INTERRUPTED, "The Minecraft server stopped during the import");
            } else if (ImportJobs.isEnded(job)) {
                finished(job);
            } else {
                follow(jobId);
            }
        } catch (ApiClientException refused) {
            refused(jobId, refused);
        } catch (ImportFailure failure) {
            reportUnlessStopping(failure);
        }
    }

    void end(String jobId, ImportEnding ending, @Nullable String detail) {
        files.discardExport(jobId);
        EndImportJobRequest request = new EndImportJobRequest(ending.wireName(), capped(detail));
        try {
            ImportJob job = calls.call(api -> api.endImport(jobId, request), lifecycle,
                    timings.getEndGiveUpAfter().toMillis()).getJob();
            if (ImportJobs.isEnded(job)) finished(job);
            else follow(jobId);
        } catch (ApiClientException refused) {
            refused(jobId, refused);
        } catch (ImportFailure failure) {
            reportUnlessStopping(failure);
        }
    }

    void follow(String jobId) {
        long announcedAt = System.nanoTime();
        try {
            while (!lifecycle.isSet()) {
                ImportJob job = calls.call(api -> api.getImportJob(jobId), lifecycle, Long.MAX_VALUE).getJob();
                if (ImportJobs.isEnded(job)) {
                    finished(job);
                    return;
                }
                long now = System.nanoTime();
                if (ImportJobs.isProcessing(job) && now - announcedAt >= timings.getAnnounceEvery().toNanos()) {
                    reporter.processing(job);
                    announcedAt = now;
                }
                lifecycle.await(timings.getPollEvery().toMillis());
            }
        } catch (ApiClientException refused) {
            refused(jobId, refused);
        } catch (ImportFailure failure) {
            reportUnlessStopping(failure);
        }
    }

    private void finished(ImportJob job) {
        reporter.result(job);
        files.forget(job.getId());
    }

    private void refused(String jobId, ApiClientException refused) {
        if (refused.getStatusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
            files.forget(jobId);
            reporter.say("import.job_gone");
        } else {
            reporter.failed(ImportProblem.REQUEST_FAILED.messageKey(), refused.getMessage());
        }
    }

    private void reportUnlessStopping(ImportFailure failure) {
        if (!lifecycle.isSet()) reporter.failed(failure.getMessageKey(), failure.getMessage());
    }

    private static @Nullable String capped(@Nullable String detail) {
        return detail != null && detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) : detail;
    }
}
