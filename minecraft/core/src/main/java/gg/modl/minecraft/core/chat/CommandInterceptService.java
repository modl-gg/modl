package gg.modl.minecraft.core.chat;

import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.punishment.PunishmentMessageService;
import gg.modl.minecraft.core.network.ModerationSessionService;

import java.util.Objects;
import java.util.UUID;

public final class CommandInterceptService {
    public enum CommandResult { ALLOWED, BLOCKED_FROZEN, BLOCKED_MUTED }

    private final Cache cache;
    private final ModerationSessionService moderation;
    private final LocaleManager localeManager;
    private final PunishmentMessageService punishmentMessageService;
    private volatile CommandMatcher mutedCommands;

    public CommandInterceptService(Cache cache, ModerationSessionService moderation, LocaleManager localeManager,
                                   PunishmentMessageService punishmentMessageService, CommandMatcher mutedCommands) {
        this.cache = cache;
        this.moderation = moderation;
        this.localeManager = localeManager;
        this.punishmentMessageService = punishmentMessageService;
        this.mutedCommands = Objects.requireNonNull(mutedCommands, "mutedCommands");
    }

    public void setMutedCommands(CommandMatcher mutedCommands) {
        this.mutedCommands = Objects.requireNonNull(mutedCommands, "mutedCommands");
    }

    public CommandResult check(UUID uuid, String commandLine) {
        if (moderation.isFrozen(uuid)) return CommandResult.BLOCKED_FROZEN;

        CachedProfile profile = cache.getPlayerProfile(uuid);
        if (profile != null && profile.isMuted() && mutedCommands.matches(commandLine)) {
            return CommandResult.BLOCKED_MUTED;
        }
        return CommandResult.ALLOWED;
    }

    public String getBlockMessage(CommandResult result, UUID uuid) {
        switch (result) {
            case BLOCKED_FROZEN:
                return localeManager.getMessage("freeze.command_blocked");
            case BLOCKED_MUTED:
                CachedProfile profile = cache.getPlayerProfile(uuid);
                return punishmentMessageService.getMuteMessage(profile != null ? profile.getActiveMute() : null);
            default:
                throw new IllegalArgumentException("An allowed command has no block message");
        }
    }
}
