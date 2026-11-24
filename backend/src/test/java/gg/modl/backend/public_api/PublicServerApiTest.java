package gg.modl.backend.public_api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PublicServerApiTest {
    private static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void setupStatusReportsTheInstanceAsClaimed() throws Exception {
        var response = api.publicGet("/v1/public/setup/status");
        JsonHelper.assertStatus(response, 200);
        assertTrue(JsonHelper.parseObject(response.body()).get("claimed").getAsBoolean());
    }
}
