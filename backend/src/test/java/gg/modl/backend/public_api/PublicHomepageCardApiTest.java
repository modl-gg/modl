package gg.modl.backend.public_api;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PublicHomepageCardApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void getVisibleCards() throws Exception {
        var response = api.publicGet("/v1/public/homepage-cards");
        JsonHelper.assertStatus(response, 200);
    }
}

