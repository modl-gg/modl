package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.api.http.request.ActionIssuer;
import gg.modl.minecraft.core.connection.PanelUrl;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.EvidenceUploadTokenResponse;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.TestHttpClients;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.impl.menus.util.ChatInputManager;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.support.TestPluginServices;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentActionCommandTest {
    private static final String UPLOAD_LINK = "Upload \"evidence\" here";
    private static final String UPLOAD_LINK_HOVER = "Opens the upload page";

    @Test
    void encodesTheDynamicUploadTokenInTheClickableUploadLink() throws Exception {
        UUID senderUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174302");
        CompletableFuture<EvidenceUploadTokenResponse> tokenFuture = new CompletableFuture<>();
        String punishmentId = "punish\"\\id\nnext";
        String token = "token\"\\line\nnext";
        FakePlatform platform = new FakePlatform()
                .register(new AbstractPlayer(senderUuid, "Staff", "203.0.113.20", true));
        Cache cache = new Cache(new CachedProfileRegistry());
        cache.cacheStaffPermissions(senderUuid, "Staff", "staff-1", "Moderator",
                Collections.singletonList(Permissions.PUNISHMENT_MODIFY_EVIDENCE));
        PluginServices services = TestPluginServices.install(cache, localeManager(),
                TestPluginServices.runningGate(cache, localeManager()), null);
        PunishmentActionCommand command = new PunishmentActionCommand(
                TestHttpClients.connected(httpClientReturning(tokenFuture, punishmentId)),
                platform,
                cache,
                localeManager(),
                PanelUrl.of("https://panel.example.com")
        );

        try {
            uploadThenResolve(command, senderUuid, tokenFuture, punishmentId, token);
        } finally {
            PluginServices.uninstall(services);
        }

        JsonObject link = JsonParser.parseString(platform.lastJson()).getAsJsonObject()
                .getAsJsonArray("extra").get(0).getAsJsonObject();
        assertEquals(UPLOAD_LINK, link.get("text").getAsString());
        assertEquals("open_url", link.getAsJsonObject("clickEvent").get("action").getAsString());
        assertEquals("https://panel.example.com/upload-evidence/" + URLEncoder.encode(token, StandardCharsets.UTF_8),
                link.getAsJsonObject("clickEvent").get("value").getAsString());
        assertEquals(UPLOAD_LINK_HOVER, link.getAsJsonObject("hoverEvent").get("value").getAsString());
    }

    @Test
    void staffWithoutTheEvidencePermissionGetNoUploadLinkAndNoEvidencePrompt() {
        UUID senderUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174303");
        FakePlatform platform = new FakePlatform().register(new AbstractPlayer(senderUuid, "Helper", "203.0.113.21", true));
        Cache cache = new Cache(new CachedProfileRegistry());
        cache.cacheStaffPermissions(senderUuid, "Helper", "staff-2", "Helper",
                Collections.singletonList(Permissions.TICKET_VIEW_ALL));
        ChatInputManager chatInput = new ChatInputManager(platform, localeManager());
        PluginServices services = TestPluginServices.install(cache, localeManager(),
                TestPluginServices.runningGate(cache, localeManager()), chatInput);
        PunishmentActionCommand command = new PunishmentActionCommand(TestHttpClients.connected(new FakeModlHttpClient()),
                platform, cache, localeManager(), PanelUrl.of("https://panel.example.com"));
        try {
            FakeCommandActor actor = new FakeCommandActor(senderUuid, "Helper");
            command.punishmentAction(actor, "upload-evidence", "PUNISH01");
            command.punishmentAction(actor, "link-evidence", "PUNISH01");

            assertFalse(chatInput.handleChat(senderUuid, "https://evidence.example/clip"));
            assertTrue(platform.sentJsonMessages().isEmpty());
        } finally {
            PluginServices.uninstall(services);
        }
    }

    private static void uploadThenResolve(PunishmentActionCommand command, UUID senderUuid,
                                          CompletableFuture<EvidenceUploadTokenResponse> tokenFuture,
                                          String punishmentId, String token) {
        command.punishmentAction(new FakeCommandActor(senderUuid, "Staff"), "upload-evidence", punishmentId);
        tokenFuture.complete(new EvidenceUploadTokenResponse(token, 200));
    }

    private static MapLocaleManager localeManager() {
        return new MapLocaleManager()
                .put("punishment_action.generating_upload", "Generating upload link")
                .put("punishment_action.upload_failed", "Upload failed")
                .put("punishment_action.upload_link", UPLOAD_LINK)
                .put("punishment_action.upload_link_hover", UPLOAD_LINK_HOVER);
    }

    private static ModlHttpClient httpClientReturning(CompletableFuture<EvidenceUploadTokenResponse> tokenFuture,
                                                      String expectedPunishmentId) {
        return new FakeModlHttpClient() {
            @Override
            public CompletableFuture<EvidenceUploadTokenResponse> createEvidenceUploadToken(String punishmentId,
                                                                                            String issuerName,
                                                                                            ActionIssuer issuer) {
                assertEquals(expectedPunishmentId, punishmentId);
                assertEquals(ActionIssuer.staff("staff-1"), issuer);
                return tokenFuture;
            }
        };
    }

}
