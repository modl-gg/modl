package gg.modl.minecraft.core.impl.menus;

import dev.simplix.cirrus.item.CirrusBaseItemStack;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.config.ReportGuiConfig;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.TestPluginServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReportChatLogMenuTest {
    private static final AbstractPlayer REPORTER =
            new AbstractPlayer(UUID.fromString("123e4567-e89b-12d3-a456-426614174500"), "Reporter", true);
    private static final AbstractPlayer TARGET =
            new AbstractPlayer(UUID.fromString("123e4567-e89b-12d3-a456-426614174501"), "Target", true);

    private LocaleManager locale;

    @BeforeEach
    void installServices() {
        locale = new LocaleManager();
        TestPluginServices.install(new Cache(new CachedProfileRegistry()), locale);
    }

    @Test
    void replayOnlyStepUsesReplayLabelsAndReplaySkullLore() {
        ReportChatLogMenu menu = menu(false, true);

        assertEquals(locale.getMessage("messages.report_capture_replay"), name(menu.get(11)));
        assertEquals(locale.getMessageList("messages.report_capture_replay_lore"), lore(menu.get(11)));
        assertEquals(locale.getMessage("messages.report_skip_replay"), name(menu.get(15)));
        assertEquals(locale.getMessageList("messages.report_skip_replay_lore"), lore(menu.get(15)));
        assertEquals(skullLore("messages.report_skull_replay"), lore(menu.get(13)));
        assertNotEquals(skullLore("messages.report_skull_chat_log"), lore(menu.get(13)));
    }

    @Test
    void chatOnlyStepKeepsChatLogSkullLore() {
        ReportChatLogMenu menu = menu(true, false);

        assertEquals(locale.getMessage("messages.report_attach_chat"), name(menu.get(11)));
        assertEquals(locale.getMessage("messages.report_skip_chat"), name(menu.get(15)));
        assertEquals(skullLore("messages.report_skull_chat_log"), lore(menu.get(13)));
    }

    @Test
    void combinedStepUsesCombinedSkullLoreAndLocalizedToggles() {
        ReportChatLogMenu menu = menu(true, true);

        assertEquals(skullLore("messages.report_skull_evidence"), lore(menu.get(4)));
        assertEquals(Collections.singletonList(locale.getMessage("messages.report_chat_click_to_attach")),
                lore(menu.get(10)));
        assertEquals(locale.getMessage("messages.report_capture_replay"), name(menu.get(16)));
        assertEquals(Arrays.asList(locale.getMessage("messages.report_replay_not_attached"), "",
                locale.getMessage("menus.common.click_to_toggle")), lore(menu.get(16)));
        assertEquals(locale.getMessage("messages.report_continue"), name(menu.get(22)));
        assertEquals(locale.getMessageList("messages.report_continue_lore"), lore(menu.get(22)));
        assertEquals(locale.getMessage("menus.common.back"), name(menu.get(26)));
    }

    private ReportChatLogMenu menu(boolean chatReport, boolean replayCapture) {
        ReportData reportData = new ReportData("Cheating", chatReport);
        reportData.setReplayCapture(replayCapture);
        return new ReportChatLogMenu(REPORTER, TARGET, new FakeModlHttpClient(), locale, new FakePlatform(),
                ReportGuiConfig.createDefault(), new ChatMessageCache(), reportData);
    }

    private List<String> skullLore(String key) {
        List<String> lines = locale.getMessageList(key, Collections.singletonMap("player", TARGET.getUsername()));
        return lines.subList(1, lines.size());
    }

    private static String name(CirrusBaseItemStack item) {
        return item.displayName().asLegacyText();
    }

    private static List<String> lore(CirrusBaseItemStack item) {
        return item.lore().stream().map(CirrusChatElement::asLegacyText).collect(Collectors.toList());
    }
}
