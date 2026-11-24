package gg.modl.backend.panel;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PanelDashboardApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void getMetrics() throws Exception {
        var response = api.panelGet("/v1/panel/dashboard/metrics");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getRecentTickets() throws Exception {
        var response = api.panelGet("/v1/panel/dashboard/recent-tickets?limit=5");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getRecentPunishments() throws Exception {
        var response = api.panelGet("/v1/panel/dashboard/recent-punishments?limit=5");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getRecentActivity() throws Exception {
        var response = api.panelGet("/v1/panel/dashboard/activity/recent?limit=10&days=7");
        JsonHelper.assertStatus(response, 200);
    }
}

