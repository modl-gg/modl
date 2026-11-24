package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.request.ActionIssuer;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.support.TestPluginServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportResolutionTest {
    private static final ActionIssuer ISSUER = ActionIssuer.staff("staff-1");

    private final MapLocaleManager locale = new MapLocaleManager()
            .withFallback(path -> path)
            .put("menus.punish_severity.reports_resolved", "resolved {count}")
            .put("menus.punish_severity.reports_not_resolved", "not resolved {count}: {reports}")
            .put("menus.punish_severity.reports_resolve_error", "error")
            .put("general.no_permission", "no permission");
    private final PluginServices services = TestPluginServices.install(new Cache(new CachedProfileRegistry()), locale);
    private final Map<String, CompletableFuture<Void>> calls = new HashMap<>();
    private final List<ActionIssuer> issuers = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final FakeModlHttpClient client = new FakeModlHttpClient() {
        @Override
        public CompletableFuture<Void> resolveReport(String reportId, String resolvedBy, String resolution, String punishmentId,
                                                     ActionIssuer issuer) {
            issuers.add(issuer);
            return calls.computeIfAbsent(reportId, id -> new CompletableFuture<>());
        }
    };

    @AfterEach
    void uninstall() {
        PluginServices.uninstall(services);
    }

    @Test
    void everyResolvedReportIsCountedOnceAllCallsFinish() {
        CompletableFuture<Void> done = resolve("R1", "R2");
        calls.get("R1").complete(null);
        assertEquals(Collections.emptyList(), messages);

        calls.get("R2").complete(null);

        assertTrue(done.isDone());
        assertEquals(Collections.singletonList("resolved 2"), messages);
        assertEquals(Arrays.asList(ISSUER, ISSUER), issuers);
    }

    @Test
    void refusedReportsAreNeverAnnouncedAsResolved() {
        CompletableFuture<Void> resolution = resolve("R1", "R2");
        calls.get("R1").completeExceptionally(new ApiClientException(403, "Forbidden"));
        calls.get("R2").completeExceptionally(new ApiClientException(403, "Forbidden"));

        assertTrue(resolution.isDone());
        assertEquals(Arrays.asList("not resolved 2: R1, R2", "no permission"), messages);
    }

    @Test
    void aMixedResultNamesWhatWasResolvedAndWhatWasNot() {
        CompletableFuture<Void> resolution = resolve("R1", "R2", "R3");
        calls.get("R2").completeExceptionally(new ApiClientException(403, "Forbidden"));
        calls.get("R3").completeExceptionally(new ApiClientException(404, "Report not found"));
        calls.get("R1").complete(null);

        assertTrue(resolution.isDone());
        assertEquals(Arrays.asList("resolved 1", "not resolved 2: R2, R3", "no permission", "error"), messages);
    }

    private CompletableFuture<Void> resolve(String... reportIds) {
        return ReportResolution.resolveLinked(client, Arrays.asList(reportIds), "Mod", "PUNISH01", ISSUER, messages::add);
    }
}
