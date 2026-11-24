package gg.modl.minecraft.api.http;

import gg.modl.minecraft.api.http.response.StatusReportResponse;

import java.util.concurrent.CompletableFuture;

public interface StatusApi {

    CompletableFuture<StatusReportResponse> getStatusReport();
}
