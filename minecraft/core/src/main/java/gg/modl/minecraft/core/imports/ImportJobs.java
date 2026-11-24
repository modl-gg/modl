package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.response.ImportJob;

import java.util.Set;

import static gg.modl.minecraft.core.util.Java8Collections.setOf;

final class ImportJobs {
    static final String BUILDING = "building_json";
    static final String UPLOADING = "uploading_json";
    static final String RECEIVING = "receiving_file";
    static final String PROCESSING = "processing_data";
    static final String COMPLETED = "completed";
    static final String FAILED = "failed";

    private static final Set<String> BEFORE_ACCEPTANCE = setOf(BUILDING, UPLOADING, RECEIVING);

    private ImportJobs() {
    }

    static boolean isActive(ImportJob job) {
        return job != null && (isBeforeAcceptance(job) || isProcessing(job));
    }

    static boolean isBeforeAcceptance(ImportJob job) {
        return job != null && job.getStatus() != null && BEFORE_ACCEPTANCE.contains(job.getStatus());
    }

    static boolean isProcessing(ImportJob job) {
        return job != null && PROCESSING.equals(job.getStatus());
    }

    static boolean isCompleted(ImportJob job) {
        return job != null && COMPLETED.equals(job.getStatus());
    }

    static boolean isEnded(ImportJob job) {
        return job != null && (COMPLETED.equals(job.getStatus()) || FAILED.equals(job.getStatus()));
    }
}
