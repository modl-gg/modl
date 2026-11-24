package gg.modl.backend.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateLimitConfigTest {
    private static final String HOSTED_IMPORT = "/v1/panel/setup/import";

    private final RateLimitConfig config = new RateLimitConfig(new BucketPool());

    @Test
    void publicMediaPostUsesDedicatedTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.PUBLIC_MEDIA_UPLOAD,
            tierFor("/v1/public/media/presign", "POST")
        );
        assertEquals(
            RateLimitConfig.RateLimitTier.PUBLIC_MEDIA_UPLOAD,
            tierFor("/v1/public/media/confirm", "POST")
        );
    }

    @Test
    void publicMediaReadRemainsStandardTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.PUBLIC_STANDARD,
            tierFor("/v1/public/media/config", "GET")
        );
    }

    @Test
    void publicTicketCreateTierUnchanged() {
        assertEquals(
            RateLimitConfig.RateLimitTier.PUBLIC_TICKET_CREATE,
            tierFor("/v1/public/tickets", "POST")
        );
    }

    @Test
    void v3MinecraftUsesMinecraftTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD,
            tierFor("/v3/minecraft/players/sync", "POST")
        );
    }

    @Test
    void v3MinecraftLoginUsesLoginTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.MINECRAFT_LOGIN,
            tierFor("/v3/minecraft/players/login", "POST")
        );
    }

    @Test
    void v1MinecraftLoginUsesLoginTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.MINECRAFT_LOGIN,
            tierFor("/v1/minecraft/players/login", "POST")
        );
    }

    @Test
    void nonLoginMinecraftPlayerRoutesStayStandardTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD,
            tierFor("/v1/minecraft/players/lookup", "POST")
        );
        assertEquals(
            RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD,
            tierFor("/v1/minecraft/players/disconnect", "POST")
        );
        assertEquals(
            RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD,
            tierFor("/v1/minecraft/players/pardon", "POST")
        );
        assertEquals(
            RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD,
            tierFor("/v1/minecraft/players/75f4b741-67df-414c-957b-a8a08222fc30/notes", "POST")
        );
        assertEquals(
            RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD,
            tierFor("/v1/minecraft/players/75f4b741-67df-414c-957b-a8a08222fc30", "GET")
        );
        assertEquals(
            RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD,
            tierFor("/v1/minecraft/punishments/create", "POST")
        );
    }

    @Test
    void publicTicketUnfinishedUsesCreateTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.PUBLIC_TICKET_CREATE,
            tierFor("/v1/public/tickets/unfinished", "POST")
        );
    }

    @Test
    void publicTicketReplyAndSubmitUseInteractTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.PUBLIC_TICKET_INTERACT,
            tierFor("/v1/public/tickets/PLAYER-123456/replies", "POST")
        );
        assertEquals(
            RateLimitConfig.RateLimitTier.PUBLIC_TICKET_INTERACT,
            tierFor("/v1/public/tickets/PLAYER-123456/submit", "POST")
        );
    }

    @Test
    void publicTicketVerifyUsesVerifyTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.PUBLIC_TICKET_VERIFY,
            tierFor("/v1/public/tickets/PLAYER-123456/verify", "POST")
        );
        assertEquals(
            RateLimitConfig.RateLimitTier.PUBLIC_TICKET_VERIFY,
            tierFor("/v1/public/tickets/PLAYER-123456/request-verification", "POST")
        );
    }

    @Test
    void publicTicketReadRemainsStandardTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.PUBLIC_STANDARD,
            tierFor("/v1/public/tickets/PLAYER-123456", "GET")
        );
    }

    @Test
    void storageObjectReadsUseGenerousTierAndUploadsUseModerateTier() {
        assertEquals(
            RateLimitConfig.RateLimitTier.STORAGE_OBJECT_READ,
            tierFor("/v1/storage/objects/evidence/75f4b741.png", "GET")
        );
        assertEquals(
            RateLimitConfig.RateLimitTier.STORAGE_OBJECT_UPLOAD,
            tierFor("/v1/storage/objects/evidence/75f4b741.png", "PUT")
        );
    }

    @Test
    void setupAndLinkEndpointsUseDedicatedTiers() {
        assertEquals(RateLimitConfig.RateLimitTier.SETUP_CLAIM, tierFor("/v1/public/setup/claim", "POST"));
        assertEquals(RateLimitConfig.RateLimitTier.SETUP_CLAIM, tierFor("/v1/public/setup/sign-in", "POST"));
        assertEquals(RateLimitConfig.RateLimitTier.PUBLIC_STANDARD, tierFor("/v1/public/setup/probe/nonce", "GET"));
        assertEquals(RateLimitConfig.RateLimitTier.PANEL_HEAVY, tierFor("/v1/panel/setup/public-url/check", "POST"));
        assertEquals(RateLimitConfig.RateLimitTier.PANEL_STANDARD, tierFor("/v1/panel/setup/steps/identity", "PUT"));
        assertEquals(RateLimitConfig.RateLimitTier.PANEL_HEAVY, tierFor("/v1/panel/settings/mail", "PUT"));
        assertEquals(RateLimitConfig.RateLimitTier.MAIL_TEST, tierFor("/v1/panel/settings/mail/test", "POST"));
        assertTrue(RateLimitConfig.RateLimitTier.MAIL_TEST.isAccountScoped());
        assertEquals(RateLimitConfig.RateLimitTier.IP_LOOKUP_TEST, tierFor("/v1/panel/settings/player-addresses/test", "POST"));
        assertTrue(RateLimitConfig.RateLimitTier.IP_LOOKUP_TEST.isAccountScoped());
        assertEquals(RateLimitConfig.RateLimitTier.DATABASE_TEST, tierFor("/v1/panel/settings/database/test", "POST"));
        assertTrue(RateLimitConfig.RateLimitTier.DATABASE_TEST.isAccountScoped());
        assertEquals(RateLimitConfig.RateLimitTier.DATABASE_MOVE, tierFor("/v1/panel/settings/database/move", "POST"));
        assertTrue(RateLimitConfig.RateLimitTier.DATABASE_MOVE.isAccountScoped());
        assertEquals(RateLimitConfig.RateLimitTier.AUTH_LOGIN_LINK, tierFor("/v1/panel/auth/link", "POST"));
        assertEquals(RateLimitConfig.RateLimitTier.ACCOUNT_MINECRAFT_LINK,
            tierFor("/v1/panel/account/minecraft-link", "POST"));
        assertEquals(RateLimitConfig.RateLimitTier.MINECRAFT_PANEL_LINK,
            tierFor("/v1/minecraft/panel/link-account", "POST"));
        assertEquals(RateLimitConfig.RateLimitTier.MINECRAFT_PANEL_LINK,
            tierFor("/v1/minecraft/panel/login-link", "POST"));
    }

    @Test
    void hostedImportRoutesUseAccountScopedImportTiers() {
        for (String read : List.of("", "/preview", "/staff", "/report")) {
            assertEquals(RateLimitConfig.RateLimitTier.IMPORT_STATUS, tierFor(HOSTED_IMPORT + read, "GET"));
        }
        assertEquals(RateLimitConfig.RateLimitTier.IMPORT_UPLOAD, tierFor(HOSTED_IMPORT + "/upload", "PUT"));
        for (String action : List.of("/upload", "/upload/complete", "/download", "/start", "/resume", "/discard", "/abandon")) {
            assertEquals(RateLimitConfig.RateLimitTier.IMPORT_START, tierFor(HOSTED_IMPORT + action, "POST"));
        }
        assertTrue(RateLimitConfig.RateLimitTier.IMPORT_STATUS.isAccountScoped());
        assertTrue(RateLimitConfig.RateLimitTier.IMPORT_UPLOAD.isAccountScoped());
        assertTrue(RateLimitConfig.RateLimitTier.IMPORT_START.isAccountScoped());
    }

    private RateLimitConfig.RateLimitTier tierFor(String path, String method) {
        return config.getTierFor(RouteGroups.classifyPath(path), method);
    }
}
