package gg.modl.minecraft.core.impl.menus;

import dev.simplix.cirrus.actionhandler.ActionHandlers;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.menu.CirrusInventoryType;
import dev.simplix.cirrus.menus.SimpleMenu;
import dev.simplix.cirrus.model.CallResult;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.config.ReportGuiConfig;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.service.ChatMessageCache;
import java.util.List;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class ReportChatLogMenu extends SimpleMenu {

    private final AbstractPlayer reporter, target;
    private final ModlHttpClient httpClient;
    private final LocaleManager locale;
    private final Platform platform;
    private final ReportGuiConfig guiConfig;
    private final ChatMessageCache chatMessageCache;
    private final ReportData reportData;

    private final boolean showChat;
    private final boolean showReplay;
    private boolean chatToggled = false;
    private boolean replayToggled = false;

    public ReportChatLogMenu(AbstractPlayer reporter, AbstractPlayer target, ModlHttpClient httpClient,
                              LocaleManager locale, Platform platform,
                              ReportGuiConfig guiConfig, ChatMessageCache chatMessageCache,
                              ReportData reportData) {
        super();
        this.reporter = reporter;
        this.target = target;
        this.httpClient = httpClient;
        this.locale = locale;
        this.platform = platform;
        this.guiConfig = guiConfig;
        this.chatMessageCache = chatMessageCache;
        this.reportData = reportData;

        this.showChat = reportData.isChatReport();
        this.showReplay = reportData.isReplayCapture();

        this.chatToggled = reportData.getChatLog() != null;
        this.replayToggled = reportData.isAttachReplay();

        title(locale.getMessage("messages.report_gui_title", mapOf("player", target.getUsername())));
        type(CirrusInventoryType.GENERIC_9X3);
        buildMenu();
    }

    private void buildMenu() {
        if (showChat && showReplay) {
            buildCombinedMenu();
        } else if (showChat) {
            buildChatOnlyMenu();
        } else {
            buildReplayOnlyMenu();
        }
    }

    private void buildCombinedMenu() {
        set(buildTargetHead("messages.report_skull_evidence").slot(4));

        CirrusItem chatItem = CirrusItem.of(
                CirrusItemType.of(chatToggled ? "minecraft:lime_terracotta" : "minecraft:orange_terracotta"),
                CirrusChatElement.ofLegacyText(locale.getMessage("messages.report_attach_chat")),
                MenuItems.lore(locale.getMessage(chatToggled
                        ? "messages.report_chat_attached"
                        : "messages.report_chat_click_to_attach"))
        ).slot(10).actionHandler("toggleChat");
        if (chatToggled) chatItem.glow();
        set(chatItem);

        List<String> replayLore = ReportRenderUtil.replayToggleLore(locale, replayToggled);
        CirrusItem replayItem = CirrusItem.of(
                CirrusItemType.of("minecraft:ender_eye"),
                CirrusChatElement.ofLegacyText(locale.getMessage("messages.report_capture_replay")),
                MenuItems.lore(replayLore)
        ).slot(16).actionHandler("toggleReplay");
        if (replayToggled) replayItem.glow();
        set(replayItem);

        set(MenuItems.localized(CirrusItemType.of("minecraft:lime_terracotta"),
                "messages.report_continue", "messages.report_continue_lore")
                .slot(22).actionHandler("continue"));

        set(MenuItems.backButton().slot(26));
    }

    private void buildChatOnlyMenu() {
        set(MenuItems.localized(CirrusItemType.of("minecraft:orange_terracotta"),
                "messages.report_attach_chat", "messages.report_attach_chat_lore")
                .slot(11).actionHandler("attachChat"));

        set(buildTargetHead("messages.report_skull_chat_log").slot(13));

        set(MenuItems.localized(CirrusItemType.of("minecraft:light_blue_terracotta"),
                "messages.report_skip_chat", "messages.report_skip_chat_lore")
                .slot(15).actionHandler("skipChat"));

        set(MenuItems.backButton().slot(22));
    }

    private void buildReplayOnlyMenu() {
        set(MenuItems.localized(CirrusItemType.of("minecraft:ender_eye"),
                "messages.report_capture_replay", "messages.report_capture_replay_lore")
                .slot(11).actionHandler("captureReplay"));

        set(buildTargetHead("messages.report_skull_replay").slot(13));

        set(MenuItems.localized(CirrusItemType.of("minecraft:light_blue_terracotta"),
                "messages.report_skip_replay", "messages.report_skip_replay_lore")
                .slot(15).actionHandler("skipReplay"));

        set(MenuItems.backButton().slot(22));
    }

    private CirrusItem buildTargetHead(String localeKey) {
        return ReportRenderUtil.buildTargetHead(locale, platform, target, localeKey);
    }

    private void openDetailsMenu(Click click) {
        ActionHandlers.openMenu(new ReportDetailsMenu(
                reporter, target, httpClient, locale, platform,
                guiConfig, chatMessageCache, reportData, this
        )).handle(click);
    }

    @Override
    protected void registerActionHandlers() {
        registerActionHandler("attachChat", click -> {
            String chatLog = chatMessageCache.getChatLogForReport(target.getUuid().toString());

            if (chatLog.isEmpty()) {
                platform.sendMessage(reporter.getUuid(),
                        locale.getMessage("messages.no_chat_logs_available", mapOf("player", target.getUsername())));
            } else {
                reportData.setChatLog(chatLog);
            }

            openDetailsMenu(click);
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("skipChat", click -> {
            openDetailsMenu(click);
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("captureReplay", click -> {
            reportData.setAttachReplay(true);
            openDetailsMenu(click);
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("skipReplay", click -> {
            openDetailsMenu(click);
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("toggleChat", click -> {
            chatToggled = !chatToggled;
            if (chatToggled) {
                String chatLog = chatMessageCache.getChatLogForReport(target.getUuid().toString());
                if (chatLog.isEmpty()) {
                    platform.sendMessage(reporter.getUuid(),
                            locale.getMessage("messages.no_chat_logs_available", mapOf("player", target.getUsername())));
                    chatToggled = false;
                } else {
                    reportData.setChatLog(chatLog);
                }
            } else {
                reportData.setChatLog(null);
            }
            buildMenu();
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("toggleReplay", click -> {
            replayToggled = !replayToggled;
            reportData.setAttachReplay(replayToggled);
            buildMenu();
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("continue", click -> {
            openDetailsMenu(click);
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("back", click -> {
            ActionHandlers.openMenu(new ReportMenu(
                    reporter, target, httpClient, locale, platform,
                    guiConfig, chatMessageCache
            )).handle(click);
            return CallResult.DENY_GRABBING;
        });
    }
}
