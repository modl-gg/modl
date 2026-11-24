package gg.modl.minecraft.api.http;

import gg.modl.minecraft.api.http.request.ActionIssuer;
import gg.modl.minecraft.api.http.response.ReportsResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ReportApi {

    CompletableFuture<ReportsResponse> getReports(String status);

    CompletableFuture<ReportsResponse> getPlayerReports(UUID playerUuid, String status);

    CompletableFuture<Void> dismissReport(String reportId, String dismissedBy, String reason, ActionIssuer issuer);

    CompletableFuture<Void> resolveReport(String reportId, String resolvedBy, String resolution, String punishmentId,
                                                   ActionIssuer issuer);
}
