package gg.modl.minecraft.core.impl.commands.staff.punishments;

import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.command.CommandActor;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.command.ConsumeRemaining;
import gg.modl.minecraft.core.command.RequiresPermission;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.Permissions;
import gg.modl.minecraft.core.staff.action.AuthorizedStaff;
import org.jspecify.annotations.Nullable;

@Command("pardon")
public class PardonCommand extends AbstractPardonCommand {
    public PardonCommand(HttpClientHolder httpClientHolder, Platform platform, Cache cache, LocaleManager localeManager) {
        super(httpClientHolder, platform, cache, localeManager);
    }

    @Override protected @Nullable String getPunishmentType() { return null; }

    @Description("Pardon all of a player's active and unstarted punishments")
    @RequiresPermission(Permissions.PUNISHMENT_MODIFY_PARDON)
    public void pardon(CommandActor actor, AuthorizedStaff issuer, @Named("target") String target,
                    @Optional @ConsumeRemaining @Nullable String reason) {
        issuePardon(actor, issuer, target, reason);
    }
}
