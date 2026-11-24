package gg.modl.minecraft.core.impl.menus;

import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.actionhandler.ActionHandlers;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.menu.CirrusInventoryType;
import dev.simplix.cirrus.menus.SimpleMenu;
import dev.simplix.cirrus.model.CallResult;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.api.http.request.CreateTicketRequest;
import gg.modl.minecraft.api.http.response.CreateTicketResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.config.ReportGuiConfig;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.service.TicketKind;
import gg.modl.minecraft.core.service.TicketService;
import gg.modl.minecraft.core.util.ApiFailures;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static gg.modl.minecraft.core.util.Java8Collections.listOf;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class ReportConfirmMenu extends SimpleMenu {
    private final AbstractPlayer reporter, target;
    private final ModlHttpClient httpClient;
    private final LocaleManager locale;
    private final Platform platform;
    private final ReportGuiConfig guiConfig;
    private final ChatMessageCache chatMessageCache;
    private final ReportData reportData;

    public ReportConfirmMenu(AbstractPlayer reporter, AbstractPlayer target, ModlHttpClient httpClient,
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

        title(locale.getMessage("messages.report_gui_title", mapOf("player", target.getUsername())));
        type(CirrusInventoryType.GENERIC_9X3);
        buildMenu();
    }

    private void buildMenu() {
        List<String> confirmLore = new ArrayList<>();
        confirmLore.add(locale.getMessageWithRawValues("messages.report_summary_reason", mapOf("reason", String.valueOf(reportData.getReason()))));
        if (reportData.getDetails() != null)
            confirmLore.add(locale.getMessageWithRawValues("messages.report_summary_details", mapOf("details", reportData.getDetails())));
        if (reportData.getChatLog() != null) confirmLore.add(locale.getMessage("messages.report_summary_chat_log"));
        if (reportData.isAttachReplay()) confirmLore.add(locale.getMessage("messages.report_summary_replay"));

        set(CirrusItem.of(
                CirrusItemType.of("minecraft:lime_terracotta"),
                CirrusChatElement.ofLegacyText(locale.getMessage("messages.report_confirm")),
                MenuItems.lore(confirmLore)
        ).slot(11).actionHandler("confirm"));

        CirrusItem confirmHead = ReportRenderUtil.buildTargetHead(locale, platform, target, "messages.report_skull_confirm");
        set(confirmHead.slot(13));

        set(CirrusItem.of(
                CirrusItemType.of("minecraft:red_terracotta"),
                CirrusChatElement.ofLegacyText(locale.getMessage("messages.report_cancel")),
                MenuItems.lore(locale.getMessageList("messages.report_cancel_lore"))
        ).slot(15).actionHandler("cancel"));

        if (reportData.isReplayCapture()) {
            boolean replayOn = reportData.isAttachReplay();
            List<String> replayLore = ReportRenderUtil.replayToggleLore(locale, replayOn);
            CirrusItem replayItem = CirrusItem.of(
                    CirrusItemType.of("minecraft:ender_eye"),
                    CirrusChatElement.ofLegacyText(locale.getMessage("messages.report_attach_replay")),
                    MenuItems.lore(replayLore)
            ).slot(4).actionHandler("toggleReplay");
            if (replayOn) replayItem.glow();
            set(replayItem);
        }

        set(MenuItems.backButton().slot(22));
    }

    @Override
    protected void registerActionHandlers() {
        registerActionHandler("confirm", click -> {
            click.clickedMenu().close();
            submitReport();
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("cancel", click -> {
            click.clickedMenu().close();
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("toggleReplay", click -> {
            reportData.setAttachReplay(!reportData.isAttachReplay());
            buildMenu();
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("back", click -> {
            ActionHandlers.openMenu(new ReportDetailsMenu(
                    reporter, target, httpClient, locale, platform,
                    guiConfig, chatMessageCache, reportData, null
            )).handle(click);

            return CallResult.DENY_GRABBING;
        });
    }

    private void submitReport() {
        StringBuilder description = new StringBuilder();
        description.append("Reason: ").append(reportData.getReason());

        if (reportData.getDetails() != null) description.append("\nDetails: ").append(reportData.getDetails());

        if (reportData.getChatLog() != null) description.append("\n\n**Chat Log:**\n```\n").append(reportData.getChatLog()).append("\n```");

        String ticketType = reportData.isChatReport() ? "chat" : "player";
        String subject = (reportData.isChatReport() ? "Chat Report" : "Player Report") + ": " + target.getUsername();

        List<String> chatMessages = null;
        if (reportData.getChatLog() != null) chatMessages = listOf(reportData.getChatLog().split("\n"));

        String createdServer = platform.getPlayerServer(reporter.getUuid());

        sendMessage(locale.getMessage("messages.submitting", mapOf("type", TicketKind.REPORT.inlineName(locale))));

        CompletableFuture<@Nullable String> replayFuture;
        ReplayService replayService = PluginServices.replay();
        if (reportData.isAttachReplay() && replayService != null && replayService.shouldAttemptCapture(target.getUuid())) {
            replayFuture = replayService.captureReplay(target.getUuid(), target.getUsername());
        } else {
            replayFuture = CompletableFuture.completedFuture(null);
        }

        List<String> finalChatMessages = chatMessages;
        MenuAsync.consume(platform, reporter.getUuid(), replayFuture.exceptionally(replayFailure -> null), replayUrl -> {
            CreateTicketRequest request = CreateTicketRequest.builder()
                    .creatorUuid(reporter.getUuid().toString())
                    .type(ticketType)
                    .creatorName(reporter.getUsername())
                    .subject(subject)
                    .description(description.toString())
                    .reportedPlayerUuid(target.getUuid().toString())
                    .reportedPlayerName(target.getUsername())
                    .priority("normal")
                    .createdServer(createdServer)
                    .chatMessages(finalChatMessages)
                    .tags(listOf("report"))
                    .replayUrl(replayUrl)
                    .build();

            CompletableFuture<CreateTicketResponse> future = httpClient.createTicket(request);

            future.thenAccept(response -> {
                String ticketId = response.getTicketId();
                if (response.isSuccess() && ticketId != null) {
                    TicketService tickets = PluginServices.ticket();
                    if (tickets != null) tickets.setCooldown(reporter.getUuid(), reportData.isChatReport() ? "chat" : "player");

                    sendMessage(locale.getMessage("messages.success", mapOf("type", TicketKind.REPORT.displayName(locale))));
                    sendMessage(locale.getMessage("messages.ticket_id", mapOf("ticketId", ticketId)));

                    if (tickets != null) tickets.sendTicketLink(reporter.getUuid(),
                            locale.getMessage("messages.view_ticket_label"), ticketId, response.getPublicUrl());
                } else {
                    sendMessage(locale.getMessage("messages.failed_submit",
                            mapOf("type", TicketKind.REPORT.inlineName(locale), "error", locale.sanitizeErrorMessage(response.getMessage()))));
                }
            }).exceptionally(throwable -> {
                if (throwable.getCause() instanceof PanelUnavailableException) {
                    sendMessage(ApiFailures.unavailable(locale, (PanelUnavailableException) throwable.getCause()));
                } else {
                    sendMessage(locale.getMessage("messages.failed_submit",
                            mapOf("type", TicketKind.REPORT.inlineName(locale), "error", locale.sanitizeErrorMessage(throwable.getMessage()))));
                }
                return null;
            });
        });
    }

    private void sendMessage(String message) {
        platform.sendMessage(reporter.getUuid(), message);
    }
}
