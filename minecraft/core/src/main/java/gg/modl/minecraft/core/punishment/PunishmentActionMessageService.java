package gg.modl.minecraft.core.punishment;

import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.ClickableJsonMessage;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class PunishmentActionMessageService {
    private static final String BUTTONS_KEY = "punishment_action.buttons.";

    private final Platform platform;
    private final LocaleManager localeManager;
    private final @Nullable String commandPath;

    public PunishmentActionMessageService(Platform platform, LocaleManager localeManager, @Nullable String commandPath) {
        this.platform = platform;
        this.localeManager = localeManager;
        this.commandPath = commandPath;
    }

    public void sendPunishmentActions(UUID playerUuid, String punishmentId) {
        String json = buildActionButtonsJson(punishmentId);
        if (json == null) return;
        platform.sendJsonMessage(playerUuid, json);
    }

    private @Nullable String buildActionButtonsJson(String punishmentId) {
        if (commandPath == null || commandPath.trim().isEmpty()) return null;

        return ClickableJsonMessage.empty()
                .extra(ClickableJsonMessage.text(localeManager.getMessageWithRawValues(BUTTONS_KEY + "label",
                        mapOf("id", punishmentId))).color("yellow"))
                .extra(actionButton("modify", "gold", "modify", punishmentId))
                .extra(ClickableJsonMessage.text(" "))
                .extra(actionButton("link_evidence", "aqua", "link-evidence", punishmentId))
                .extra(ClickableJsonMessage.text(" "))
                .extra(actionButton("upload_evidence", "green", "upload-evidence", punishmentId))
                .toJson();
    }

    private ClickableJsonMessage actionButton(String buttonKey, String color, String subcommand, String punishmentId) {
        return ClickableJsonMessage.text(localeManager.getMessage(BUTTONS_KEY + buttonKey))
                .color(color)
                .runCommand("/" + commandPath + " " + subcommand + " " + punishmentId)
                .hoverText(localeManager.getMessage(BUTTONS_KEY + buttonKey + "_hover"));
    }
}
