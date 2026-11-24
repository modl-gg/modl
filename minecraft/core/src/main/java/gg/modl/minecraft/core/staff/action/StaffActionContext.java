package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.network.ModerationSessionService;
import gg.modl.minecraft.core.staff.StaffCommandUtil;
import gg.modl.minecraft.core.staff.StaffCommandUtil.StaffDisplay;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@Getter
@RequiredArgsConstructor
public final class StaffActionContext {
    private final Platform platform;
    private final Cache cache;
    private final LocaleManager localeManager;
    private final ModerationSessionService moderation;

    StaffDisplay displayOf(StaffActor actor) {
        return StaffCommandUtil.resolveDisplay(actor.uuid(), platform, cache,
                localeManager.getMessage("general.console"), localeManager.getStaffWord(), false);
    }

    void reply(StaffActor actor, String messageKey) {
        actor.reply(localeManager.getMessage(messageKey));
    }

    void reply(StaffActor actor, String messageKey, String player) {
        actor.reply(localeManager.getMessage(messageKey, mapOf("player", player)));
    }

    void broadcast(String messageKey, StaffDisplay display) {
        broadcast(messageKey, display, null);
    }

    void broadcast(String messageKey, StaffDisplay display, @Nullable String player) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("staff", display.getPanelName());
        placeholders.put("in-game-name", display.getInGameName());
        if (player != null) placeholders.put("player", player);
        platform.staffBroadcast(localeManager.getMessage(messageKey, placeholders));
    }
}
