package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.api.http.request.ActionIssuer;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.TestPluginServices;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportDismissalTest {
    private final UUID staff = UUID.randomUUID();
    private final Cache cache = new Cache(new CachedProfileRegistry());
    private final LocaleManager locale = new LocaleManager();
    private final PluginServices services = TestPluginServices.install(cache, locale,
            TestPluginServices.runningGate(cache, locale), null);
    private final List<String> dismissed = new ArrayList<>();
    private final List<ActionIssuer> issuers = new ArrayList<>();
    private final FakeModlHttpClient client = new FakeModlHttpClient() {
        @Override
        public CompletableFuture<Void> dismissReport(String reportId, String dismissedBy, String reason, ActionIssuer issuer) {
            dismissed.add(reportId);
            issuers.add(issuer);
            return CompletableFuture.completedFuture(null);
        }
    };

    @AfterEach
    void uninstall() {
        PluginServices.uninstall(services);
    }

    @Test
    void staffWithoutTicketClosingCannotDismissReports() {
        grant(Permissions.TICKET_VIEW_ALL);

        ReportDismissal.dismiss(new FakePlatform(), client, staff, "Helper", "REPORT01", () -> { });

        assertEquals(Collections.emptyList(), dismissed);
    }

    @Test
    void staffWhoMayCloseTicketsDismissTheReport() {
        grant(Permissions.TICKET_CLOSE_ALL);

        ReportDismissal.dismiss(new FakePlatform(), client, staff, "Moderator", "REPORT01", () -> { });

        assertEquals(Collections.singletonList("REPORT01"), dismissed);
        assertEquals(Collections.singletonList(ActionIssuer.staff("staff-1")), issuers);
    }

    private void grant(String permission) {
        cache.cacheStaffPermissions(staff, "Staff", "staff-1", "Helper", Collections.singletonList(permission));
    }
}
