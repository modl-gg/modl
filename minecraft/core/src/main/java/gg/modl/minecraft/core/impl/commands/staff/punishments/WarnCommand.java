package gg.modl.minecraft.core.impl.commands.staff.punishments;

import gg.modl.minecraft.api.PlayerNoteKind;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.command.CommandActor;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.http.request.CreatePlayerNoteRequest;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.command.ConsumeRemaining;
import gg.modl.minecraft.core.command.RequiresPermission;
import gg.modl.minecraft.core.command.StaffOnly;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.punishment.PunishmentFlagParser;
import gg.modl.minecraft.core.util.ApiFailures;
import gg.modl.minecraft.core.util.CommandUtil;
import gg.modl.minecraft.core.util.Permissions;
import gg.modl.minecraft.core.staff.StaffAudiences;
import gg.modl.minecraft.core.staff.action.AuthorizedStaff;
import lombok.RequiredArgsConstructor;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
@Command("warn")
public class WarnCommand {
    private final HttpClientHolder httpClientHolder;
    private final Platform platform;
    private final Cache cache;
    private final LocaleManager localeManager;
    private final StaffAudiences staffAudiences;

    @StaffOnly
    @RequiresPermission(Permissions.PLAYER_WARN)
    public void warn(CommandActor actor, AuthorizedStaff issuer, @Named("target") Account target,
                     @Optional @ConsumeRemaining @Nullable String args) {
        if (target == null) {
            actor.reply(localeManager.getPunishmentMessage("general.player_not_found", mapOf()));
            return;
        }

        final PunishmentFlagParser.Flags warnArgs = PunishmentFlagParser.builder().silent(true).build().parse(args);
        if (warnArgs.getReason().isEmpty()) {
            actor.reply(localeManager.getPunishmentMessage("general.invalid_syntax", mapOf()));
            return;
        }

        final String issuerName = CommandUtil.resolveActorName(actor, cache, platform);

        CreatePlayerNoteRequest noteRequest = new CreatePlayerNoteRequest(
            target.getMinecraftUuid().toString(), issuerName, issuer.actionIssuer(),
            PlayerNoteKind.WARNING, warnArgs.getReason()
        );

        httpClientHolder.getClient().createPlayerNote(noteRequest).thenAccept(response -> {
            final String targetName = target.getUsernames().isEmpty()
                    ? target.getMinecraftUuid().toString()
                    : target.getUsernames().get(0).getUsername();
            platform.runOnMainThread(() -> {
                notifyTargetIfOnline(target, issuerName, warnArgs.getReason());

                actor.reply(localeManager.getMessage("warn.success", mapOf(
                    "target", targetName, "reason", warnArgs.getReason()
                )));

                if (!warnArgs.isSilent()) {
                    platform.staffBroadcast(localeManager.getMessage("warn.staff_notification", mapOf(
                        "issuer", issuerName, "target", targetName, "reason", warnArgs.getReason()
                    )), staffAudiences.playerViewers());
                }
            });
        }).exceptionally(throwable -> {
            platform.runOnMainThread(() -> actor.reply(ApiFailures.describe(localeManager, "warn.error", throwable)));
            return null;
        });
    }

    private void notifyTargetIfOnline(Account target, String issuerName, String reason) {
        AbstractPlayer targetPlayer = platform.getAbstractPlayer(target.getMinecraftUuid(), false);
        if (targetPlayer != null && targetPlayer.isOnline()) {
            platform.sendMessage(target.getMinecraftUuid(), localeManager.getMessage("warn.player_message", mapOf(
                "issuer", issuerName, "reason", reason
            )));
        }
    }
}
