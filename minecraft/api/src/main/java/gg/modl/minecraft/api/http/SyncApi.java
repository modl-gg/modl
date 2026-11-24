package gg.modl.minecraft.api.http;

import gg.modl.minecraft.api.http.request.SyncRequest;
import gg.modl.minecraft.api.http.response.DashboardStatsResponse;
import gg.modl.minecraft.api.http.response.SyncResponse;

import java.util.concurrent.CompletableFuture;

public interface SyncApi {

    CompletableFuture<SyncResponse> sync(SyncRequest request);

    CompletableFuture<DashboardStatsResponse> getDashboardStats();
}
