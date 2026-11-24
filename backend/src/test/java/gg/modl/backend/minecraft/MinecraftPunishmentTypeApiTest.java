package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class MinecraftPunishmentTypeApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void getPunishmentTypes() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/punishments/types");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("data"));
    }
}
