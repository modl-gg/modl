package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.CapturedOperatorNotices;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.TestServerCredentials;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PanelAuthApiTest {

    private static final String SESSION_COOKIE = "MODL_SESSION=";

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void sendEmailCode() throws Exception {
        var response = api.publicPost("/v1/panel/auth/send-email-code", Map.of("email", "nobody@example.com"));

        JsonHelper.assertStatus(response, 200);
        JsonHelper.assertJsonHas(response, "success");
    }

    @Test
    void verifyEmailCode() throws Exception {
        var session = signInWithEmailCode("code-login@example.com");

        JsonHelper.assertStatus(session.panelGet("/v1/panel/auth/me"), 200);
    }

    @Test
    void logout() throws Exception {
        var session = signInWithEmailCode("code-logout@example.com");

        JsonHelper.assertStatus(session.panelPost("/v1/panel/auth/logout", Map.of()), 200);
        JsonHelper.assertStatus(session.panelGet("/v1/panel/auth/me"), 401);
    }

    private static ApiClient signInWithEmailCode(String email) throws Exception {
        assumeFalse(TestServerCredentials.usesExternalServer(), "Reading the login code needs the in-process server");
        JsonHelper.assertSuccess(api.panelPost("/v1/panel/staff", Map.of(
            "email", email, "username", email.substring(0, email.indexOf('@')).replace("-", ""), "role", "Helper")));
        String code;
        try (CapturedOperatorNotices notices = new CapturedOperatorNotices()) {
            JsonHelper.assertStatus(api.publicPost("/v1/panel/auth/send-email-code", Map.of("email", email)), 200);
            code = notices.awaitMatch(Pattern.compile("Login code for " + Pattern.quote(email) + ": (\\S+)")).group(1);
        }
        var verified = api.publicPost("/v1/panel/auth/verify-email-code", Map.of("email", email, "code", code));
        JsonHelper.assertStatus(verified, 200);
        String cookie = verified.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith(SESSION_COOKIE))
            .findFirst()
            .orElseThrow();
        return api.withSession(cookie.substring(SESSION_COOKIE.length(), cookie.indexOf(';')),
            JsonHelper.parseObject(verified.body()).get("sessionKey").getAsString());
    }

    @Test
    void getMe() throws Exception {
        var response = api.panelGet("/v1/panel/auth/me");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("id") || json.has("email"));
    }

    @Test
    void getPermissions() throws Exception {
        var response = api.panelGet("/v1/panel/auth/permissions");
        JsonHelper.assertStatus(response, 200);
        var arr = JsonHelper.parseArrayField(response.body(), "permissions");
        assertNotNull(arr);
    }

    @Test
    void updateProfile() throws Exception {
        // Get current profile first
        var meResponse = api.panelGet("/v1/panel/auth/me");
        var me = JsonHelper.parseObject(meResponse.body());
        String currentUsername = me.has("username") ? me.get("username").getAsString() : "test";

        // Update with same values (idempotent)
        var response = api.panelPatch("/v1/panel/auth/profile", Map.of(
            "username", currentUsername
        ));
        JsonHelper.assertStatus(response, 200);
    }
}

