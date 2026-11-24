package gg.modl.minecraft.api.http;

import gg.modl.minecraft.api.http.request.EndImportJobRequest;
import gg.modl.minecraft.api.http.request.ExportProgressRequest;
import gg.modl.minecraft.api.http.request.StartImportJobRequest;
import gg.modl.minecraft.api.http.response.ImportJobResponse;
import gg.modl.minecraft.api.http.response.ImportStatusResponse;

import java.util.concurrent.CompletableFuture;

public interface ImportApi {

    CompletableFuture<ImportJobResponse> startImport(StartImportJobRequest request);

    CompletableFuture<ImportStatusResponse> getImportStatus();

    CompletableFuture<ImportJobResponse> getImportJob(String jobId);

    CompletableFuture<ImportJobResponse> reportExportProgress(String jobId,
                                                                       ExportProgressRequest request);

    CompletableFuture<ImportJobResponse> endImport(String jobId, EndImportJobRequest request);
}
