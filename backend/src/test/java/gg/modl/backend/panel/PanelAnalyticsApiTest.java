package gg.modl.backend.panel;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PanelAnalyticsApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void getActivePunishmentsSortedAndFiltered() throws Exception {
        var response = api.panelGet("/v1/panel/audit/punishments?status=all&sort=duration&direction=asc&evidence=no");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getAuditPunishmentTypes() throws Exception {
        var response = api.panelGet("/v1/panel/audit/punishments/types");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getTicketAnalytics() throws Exception {
        var response = api.panelGet("/v1/panel/analytics/tickets?period=30d");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getPunishmentAnalytics() throws Exception {
        var response = api.panelGet("/v1/panel/analytics/punishments?period=30d");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getAuditLogs() throws Exception {
        var response = api.panelGet("/v1/panel/analytics/audit-logs?period=7d");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getStaffPerformance() throws Exception {
        var response = api.panelGet("/v1/panel/audit/staff-performance?period=30d");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getPlayerActivity() throws Exception {
        var response = api.panelGet("/v1/panel/analytics/player-activity?period=30d");
        JsonHelper.assertStatus(response, 200);
    }
}

