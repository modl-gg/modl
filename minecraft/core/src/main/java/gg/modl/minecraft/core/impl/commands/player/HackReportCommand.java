package gg.modl.minecraft.core.impl.commands.player;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.command.ConsumeRemaining;
import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.HackReportActions;
import gg.modl.minecraft.core.staff.action.StaffActors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.command.CommandActor;

@RequiredArgsConstructor
public class HackReportCommand {
    private final Platform platform;
    private final LocaleManager localeManager;
    private final HackReportActions hackReportActions;

    @Command("hackreport")
    @Description("Report a player for cheating/hacking")
    @PlayerOnly
    public void hackReport(CommandActor actor, @Named("player") String targetName, @Optional @ConsumeRemaining @Nullable String details) {
        AbstractPlayer target = platform.getAbstractPlayer(targetName, false);
        if (target == null) {
            actor.reply(localeManager.getMessage("general.player_not_found"));
            return;
        }
        hackReportActions.report(StaffActors.of(actor), target, details);
    }
}
