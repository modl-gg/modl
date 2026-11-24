package gg.modl.minecraft.core.chat;

import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.config.ConfigManager.StaffChatConfig;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.impl.menus.util.ChatInputManager;
import gg.modl.minecraft.core.network.ModerationSessionService;
import gg.modl.minecraft.core.punishment.PunishmentMessageService;
import gg.modl.minecraft.core.service.ChatCommandLogPolicy;
import gg.modl.minecraft.core.service.ChatCommandLogService;
import gg.modl.minecraft.core.service.ChatManagementService;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.service.NetworkChatInterceptService;
import gg.modl.minecraft.core.service.Staff2faService;
import gg.modl.minecraft.core.service.StaffChatService;
import gg.modl.minecraft.core.service.StaffChatService.ChatMode;
import gg.modl.minecraft.core.staff.action.StaffActionGate;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.support.TestPluginServices;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceTest {
    private static final String SERVER = "survival";
    private static final String PASSWORD = "/login hunter2";

    private final UUID player = UUID.randomUUID();
    private final FakePlatform platform = new FakePlatform();
    private final CachedProfileRegistry registry = new CachedProfileRegistry();
    private final Cache cache = new Cache(registry);
    private final MapLocaleManager locale = new MapLocaleManager().withFallback(path -> path);
    private final ChatMessageCache chatMessages = new ChatMessageCache();
    private final ChatCommandLogService chatLogs = new ChatCommandLogService();
    private final StaffChatService staffChat = new StaffChatService(registry);
    private final NetworkChatInterceptService interceptors = new NetworkChatInterceptService(registry);
    private final ChatInputManager chatInput = new ChatInputManager(platform, locale);
    private final TwoFactor twoFactor = new TwoFactor();
    private final List<String> replies = new ArrayList<>();
    private final PluginServices services = TestPluginServices.install(cache, locale, null, chatInput);
    private final ChatService chat = new ChatService(platform, cache, locale, chatMessages, staffChat,
            new StaffChatConfig(), new ChatManagementService(registry), new ModerationSessionService(), chatLogs,
            interceptors, new PunishmentMessageService(locale,
                    PanelUrl.of("https://panel.example.com")),
            new StaffActionGate(() -> CoreState.RUNNING, cache, locale, twoFactor, actor -> false));

    ChatServiceTest() {
        registry.createProfile(player);
    }

    @AfterEach
    void uninstall() {
        PluginServices.uninstall(services);
    }

    @Test
    void chatCancelledByAnotherPluginIsNotTakenAsMenuInput() {
        AtomicReference<String> typed = new AtomicReference<>();
        chatInput.requestInput(player, "prompt", typed::set, () -> { });

        assertEquals(ChatService.Result.CANCELLED, handle(PASSWORD, true));

        assertNull(typed.get());
        chatInput.handleChat(player, "answer");
        assertEquals("answer", typed.get());
    }

    @Test
    void cancelledChatIsNeverLoggedOrShownToInterceptors() {
        UUID interceptor = staff(Permissions.INTERCEPT);
        interceptors.toggle(interceptor);

        handle(PASSWORD, true);
        chat.recordChat(player, "Player", PASSWORD, SERVER, true);

        assertTrue(chatLogs.drainChatBuffer().isEmpty());
        assertEquals("", chatMessages.getChatLogForReport(player.toString()));
        assertTrue(platform.sentMessages().isEmpty());
    }

    @Test
    void chatReportsKeepTheirBufferWhenChatLoggingIsOff() {
        chatLogs.setPolicy(new ChatCommandLogPolicy(false, true, CommandMatcher.none()));

        chat.recordChat(player, "Player", "hello", SERVER, false);

        assertTrue(chatLogs.drainChatBuffer().isEmpty());
        assertTrue(chatMessages.getChatLogForReport(player.toString()).endsWith("Player: hello"));
    }

    @Test
    void deliveredChatIsLoggedAndShownOnlyToInterceptorsWhoStillHoldThePermission() {
        UUID allowed = staff(Permissions.INTERCEPT);
        UUID demoted = staff(Permissions.TICKET_VIEW_ALL);
        interceptors.toggle(allowed);
        interceptors.toggle(demoted);

        assertEquals(ChatService.Result.ALLOWED, handle("hello", false));
        chat.recordChat(player, "Player", "hello", SERVER, false);

        assertEquals(1, chatLogs.drainChatBuffer().size());
        assertEquals(1, platform.sentMessages().size());
        assertEquals(allowed, platform.sentMessages().get(0).uuid());
    }

    @Test
    void aDemotedStaffMemberInStaffChatModeIsSwitchedBackAndTheMessageGoesNowhere() {
        staffChat.setMode(player, ChatMode.STAFF);

        assertEquals(ChatService.Result.CANCELLED, handle("secret plan", false));

        assertEquals(ChatMode.NORMAL, staffChat.getMode(player));
        assertTrue(platform.staffBroadcasts().isEmpty());
        assertEquals(Collections.singletonList("staff_chat.mode_lost"), replies);
    }

    @Test
    void staffWhoHaveNotPassedTwoFactorCannotPostToStaffChat() {
        twoFactor.enabled = true;
        grant(player, Permissions.MOD_ACTIONS);
        staffChat.setMode(player, ChatMode.STAFF);

        assertEquals(ChatService.Result.CANCELLED, handle("from mode", false));
        assertEquals(ChatService.Result.CANCELLED, handle("!with prefix", false));

        assertTrue(platform.staffBroadcasts().isEmpty());
        assertEquals(ChatMode.NORMAL, staffChat.getMode(player));
    }

    @Test
    void verifiedStaffStillPostToStaffChat() {
        twoFactor.enabled = true;
        twoFactor.verified = true;
        grant(player, Permissions.MOD_ACTIONS);

        assertEquals(ChatService.Result.CANCELLED, handle("!hello team", false));

        assertEquals(1, platform.staffBroadcasts().size());
    }

    private ChatService.Result handle(String message, boolean cancelled) {
        return chat.handleChat(player, "Player", message, SERVER, cancelled, replies::add);
    }

    private UUID staff(String permission) {
        UUID uuid = UUID.randomUUID();
        registry.createProfile(uuid);
        grant(uuid, permission);
        return uuid;
    }

    private void grant(UUID uuid, String permission) {
        cache.cacheStaffPermissions(uuid, "Staff", "staff-" + uuid, "Helper", Collections.singletonList(permission));
    }

    private static final class TwoFactor extends Staff2faService {
        private boolean enabled;
        private boolean verified;

        private TwoFactor() {
            super(null, null);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public boolean isAuthenticated(UUID uuid) {
            return !enabled || verified;
        }
    }
}
