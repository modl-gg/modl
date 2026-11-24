package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.ActionIssuer;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.ApiFailures;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class ReportResolution {
    private static final String RESOLUTION = "Report accepted - punishment issued";
    private static final String KEY = "menus.punish_severity.";

    private ReportResolution() {}

    public static CompletableFuture<Void> resolveLinked(ModlHttpClient httpClient, List<String> reportIds, String viewerName,
                                                       String punishmentId, ActionIssuer issuer, Consumer<String> sendMessage) {
        List<CompletableFuture<Throwable>> failures = new ArrayList<>();
        for (String reportId : reportIds) {
            failures.add(httpClient.resolveReport(reportId, viewerName, RESOLUTION, punishmentId, issuer)
                    .<Throwable>thenApply(resolved -> null)
                    .exceptionally(failure -> failure));
        }
        return CompletableFuture.allOf(failures.toArray(new CompletableFuture<?>[0]))
                .thenRun(() -> announce(reportIds, failures, sendMessage));
    }

    private static void announce(List<String> reportIds, List<CompletableFuture<Throwable>> failures,
                                 Consumer<String> sendMessage) {
        LocaleManager locale = PluginServices.locale();
        List<String> unresolved = new ArrayList<>();
        Set<String> causes = new LinkedHashSet<>();
        for (int i = 0; i < reportIds.size(); i++) {
            Throwable failure = failures.get(i).join();
            if (failure == null) continue;
            unresolved.add(reportIds.get(i));
            causes.add(ApiFailures.describe(locale, KEY + "reports_resolve_error", failure));
        }

        int resolvedCount = reportIds.size() - unresolved.size();
        if (resolvedCount > 0) {
            sendMessage.accept(locale.getMessage(KEY + "reports_resolved", mapOf("count", String.valueOf(resolvedCount))));
        }
        if (unresolved.isEmpty()) return;
        sendMessage.accept(locale.getMessage(KEY + "reports_not_resolved",
                mapOf("count", String.valueOf(unresolved.size()), "reports", String.join(", ", unresolved))));
        causes.forEach(sendMessage);
    }
}
