package gg.modl.backend.public_api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PublicStaffApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void acceptInvitationWithInvalidTokenIsRejected() throws Exception {
        var response = api.publicPost("/v1/public/staff/invitations/accept", Map.of("token", "invalid-token"));
        int status = response.statusCode();
        assertTrue(status == 400, "Expected 400 but got " + status);
    }
}

