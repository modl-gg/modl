package gg.modl.minecraft.core.chat;

import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.network.ModerationSessionService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandInterceptServiceTest {
    private final UUID player = UUID.randomUUID();
    private final CachedProfileRegistry registry = new CachedProfileRegistry();
    private final ModerationSessionService moderation = new ModerationSessionService();
    private final CommandInterceptService intercept = new CommandInterceptService(new Cache(registry), moderation,
            null, null, CommandMatcher.of(Arrays.asList("msg", "teammsg"),
                    label -> "m".equals(label) ? Arrays.asList("m", "msg") : Collections.singleton(label)));

    @Test
    void anUnmutedPlayerMayUseEveryCommand() {
        registry.createProfile(player);

        assertEquals(CommandInterceptService.CommandResult.ALLOWED, intercept.check(player, "/msg Notch hi"));
    }

    @Test
    void aMutedPlayerCannotWhisperThroughANamespaceOrAnAlias() {
        mute();

        assertEquals(CommandInterceptService.CommandResult.BLOCKED_MUTED, intercept.check(player, "/msg Notch hi"));
        assertEquals(CommandInterceptService.CommandResult.BLOCKED_MUTED, intercept.check(player, "/minecraft:msg Notch hi"));
        assertEquals(CommandInterceptService.CommandResult.BLOCKED_MUTED, intercept.check(player, "/Essentials:MSG Notch hi"));
        assertEquals(CommandInterceptService.CommandResult.BLOCKED_MUTED, intercept.check(player, "/m Notch hi"));
        assertEquals(CommandInterceptService.CommandResult.BLOCKED_MUTED, intercept.check(player, "/teammsg hi"));
    }

    @Test
    void aMutedPlayerKeepsOtherCommands() {
        mute();

        assertEquals(CommandInterceptService.CommandResult.ALLOWED, intercept.check(player, "/spawn"));
        assertEquals(CommandInterceptService.CommandResult.ALLOWED, intercept.check(player, "/msgtoggle"));
    }

    @Test
    void reloadedMutedCommandsApplyAtOnce() {
        mute();
        intercept.setMutedCommands(CommandMatcher.of(Collections.singletonList("spawn"), Collections::singleton));

        assertEquals(CommandInterceptService.CommandResult.BLOCKED_MUTED, intercept.check(player, "/spawn"));
        assertEquals(CommandInterceptService.CommandResult.ALLOWED, intercept.check(player, "/msg Notch hi"));
    }

    private void mute() {
        registry.createProfile(player).setActiveMute(
                new SimplePunishment("MUTE", "MUTE", null, "Spam", "MUTE0001", null, null, null, true, 1, false));
    }
}
