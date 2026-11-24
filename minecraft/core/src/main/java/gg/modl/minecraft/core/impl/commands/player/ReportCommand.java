package gg.modl.minecraft.core.impl.commands.player;

import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.command.CommandActor;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.AsyncCommandExecutor;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.service.TicketService;
import gg.modl.minecraft.core.config.GuiConfigSource;
import gg.modl.minecraft.core.config.ReportGuiConfig;
import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.impl.menus.ReportMenu;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.service.ChatMessageCache;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@RequiredArgsConstructor
public class ReportCommand {
    private final AsyncCommandExecutor commandExecutor;
    private final Platform platform;
    private final HttpClientHolder httpClientHolder;
    private final LocaleManager localeManager;
    private final ChatMessageCache chatMessageCache;
    private final TicketService ticketUtil;
    private final GuiConfigSource guiConfigs;

    @Command("report")
    @Description("Report a player")
    @PlayerOnly
    public void report(CommandActor actor, AbstractPlayer targetPlayer) {
        if (ticketUtil.checkCooldown(actor, "player")) return;

        if (!platform.areMenusAvailable()) {
            actor.reply(localeManager.getMessage("general.gui_unavailable"));
            return;
        }

        AbstractPlayer reporter = platform.getAbstractPlayer(actor.uniqueId(), false);

        if (reporter == null) {
            actor.reply(localeManager.getMessage("general.player_not_found"));
            return;
        }

        if (targetPlayer == null) {
            actor.reply(localeManager.getMessage("general.player_not_found"));
            return;
        }

        if (ticketUtil.denySelfReport(actor, reporter, targetPlayer)) return;

        if (!targetPlayer.isOnline()) {
            actor.reply(localeManager.getMessage("messages.player_not_online"));
            return;
        }

        commandExecutor.execute(() -> {
            ReportGuiConfig guiConfig = guiConfigs.getReportGuiConfig();

            UUID senderUuid = actor.uniqueId();
            ReportMenu menu = new ReportMenu(
                reporter, targetPlayer, httpClientHolder.getClient(), localeManager, platform,
                guiConfig, chatMessageCache
            );
            CirrusPlayerWrapper player = platform.getPlayerWrapper(senderUuid);
            menu.display(player);
        });
    }
}
