package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.staff.action.AuthorizedStaff;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import gg.modl.minecraft.core.util.ApiFailures;

import java.util.UUID;

public final class ReportDismissal {
    private static final String REASON = "Insufficient evidence";

    private ReportDismissal() {}

    public static void dismiss(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                               String reportId, Runnable onDismissed) {
        new StaffViewer(platform, viewerUuid).authorizeAs(StaffRequirements.CLOSE_REPORTS)
                .ifPresent(issuer -> dismiss(platform, httpClient, viewerUuid, viewerName, reportId, issuer, onDismissed));
    }

    private static void dismiss(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                                String reportId, AuthorizedStaff issuer, Runnable onDismissed) {
        platform.sendMessage(viewerUuid, PluginServices.locale().getMessage("menus.common.dismissing_report"));
        httpClient.dismissReport(reportId, viewerName, REASON, issuer.actionIssuer()).thenAccept(v -> {
            platform.sendMessage(viewerUuid, PluginServices.locale().getMessage("menus.common.report_dismissed"));
            onDismissed.run();
        }).exceptionally(e -> {
            platform.sendMessage(viewerUuid,
                    ApiFailures.describe(PluginServices.locale(), "menus.common.dismiss_report_failed", e));
            return null;
        });
    }
}
