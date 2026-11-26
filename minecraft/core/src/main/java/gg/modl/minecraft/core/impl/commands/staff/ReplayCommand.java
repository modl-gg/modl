package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.command.RequiresPermission;
import gg.modl.minecraft.core.command.StaffOnly;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.util.ClickableJsonMessage;
import gg.modl.minecraft.core.util.CommandUtil;
import gg.modl.minecraft.core.util.Permissions;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.command.CommandActor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@Command("replay")
@StaffOnly
public class ReplayCommand {
    private final Platform platform;
    private final LocaleManager localeManager;
    private final PanelUrl panelUrl;
    private final ReplayRecordingReplies recordingReplies;

    public ReplayCommand(Platform platform, LocaleManager localeManager, PanelUrl panelUrl) {
        this.platform = platform;
        this.localeManager = localeManager;
        this.panelUrl = panelUrl;
        this.recordingReplies = new ReplayRecordingReplies(localeManager);
    }

    public void help(CommandActor actor) {
        for (String line : localeManager.getMessageList("replay.usage")) {
            actor.reply(line);
        }
    }

    @Subcommand("status")
    @Description("Check replay recording status for a player")
    @RequiresPermission(Permissions.MOD_ACTIONS)
    public void status(CommandActor actor, @Optional @Named("player") @Nullable String targetName) {
        changeRecording(actor, targetName, ReplayService::recordingStatus, recordingReplies::status, recordingReplies::statusFailed);
    }

    @Subcommand("record")
    @Description("Start recording a player")
    @RequiresPermission(Permissions.MOD_ACTIONS)
    public void record(CommandActor actor, @Named("player") String targetName) {
        changeRecording(actor, targetName, ReplayService::startRecording, recordingReplies::started, recordingReplies::changeFailed);
    }

    @Subcommand("stop")
    @Description("Stop a recording started with /replay record")
    @RequiresPermission(Permissions.MOD_ACTIONS)
    public void stop(CommandActor actor, @Named("player") String targetName) {
        changeRecording(actor, targetName, ReplayService::stopRecording, recordingReplies::stopped, recordingReplies::changeFailed);
    }

    @Subcommand("capture")
    @Description("Capture and upload a replay for a player")
    @RequiresPermission(Permissions.MOD_ACTIONS)
    public void capture(CommandActor actor, @Optional @Named("player") @Nullable String targetName) {
        ReplayService replayService = PluginServices.replay();
        if (replayService == null) {
            actor.reply(localeManager.getMessage("replay.not_available"));
            return;
        }

        UUID targetUuid = resolveTargetUuid(actor, targetName);
        if (targetUuid == null) return;

        String resolvedName = resolveTargetName(targetUuid, targetName);

        actor.reply(localeManager.getMessage("replay.capturing", mapOf("player", resolvedName)));

        replayService.captureReplayResult(targetUuid, resolvedName).thenAccept(result -> {
            String replayId = result.getReplayId();
            if (result.getStatus() == ReplayCaptureStatus.OK && replayId != null) {
                String replayLink = panelUrl.replay(replayId);
                actor.reply(localeManager.getMessage("replay.capture_success", mapOf("player", resolvedName)));
                if (!CommandUtil.isConsole(actor)) {
                    String clickText = localeManager.getMessage("replay.click_to_view");
                    String hoverText = localeManager.getMessage("replay.click_to_view_hover");
                    String json = ClickableJsonMessage.empty()
                            .extra(ClickableJsonMessage.text(clickText).color("aqua").underlined(true)
                                    .openUrl(replayLink).hoverText(hoverText))
                            .toJson();
                    platform.runOnMainThread(() -> platform.sendJsonMessage(actor.uniqueId(), json));
                } else {
                    actor.reply(localeManager.getMessage("replay.capture_link", mapOf("url", replayLink)));
                }
            } else {
                actor.reply(localeManager.getMessage(statusMessageKey(result.getStatus()), mapOf("player", resolvedName)));
            }
        }).exceptionally(ex -> {
            actor.reply(localeManager.getMessage("replay.capture_failed", mapOf("player", resolvedName)));
            return null;
        });
    }

    private void changeRecording(CommandActor actor, @Nullable String targetName, RecordingRequest request,
                                 BiFunction<ReplayRecordingResult, String, List<String>> replies,
                                 Function<String, List<String>> failed) {
        ReplayService replayService = PluginServices.replay();
        if (replayService == null) {
            actor.reply(localeManager.getMessage("replay.not_available"));
            return;
        }
        UUID targetUuid = resolveTargetUuid(actor, targetName);
        if (targetUuid == null) return;
        String resolvedName = resolveTargetName(targetUuid, targetName);
        request.send(replayService, targetUuid, resolvedName)
                .thenAccept(result -> replyAll(actor, replies.apply(result, resolvedName)))
                .exceptionally(failure -> {
                    replyAll(actor, failed.apply(resolvedName));
                    return null;
                });
    }

    private static void replyAll(CommandActor actor, List<String> lines) {
        for (String line : lines) actor.reply(line);
    }

    private String statusMessageKey(ReplayCaptureStatus status) {
        if (status == ReplayCaptureStatus.NO_SERVER_CONNECTED) return "replay.no_server_connected";
        if (status == ReplayCaptureStatus.NO_ACTIVE_RECORDING || status == ReplayCaptureStatus.NOT_LOCAL) {
            return "replay.no_active_recording";
        }
        return "replay.capture_failed";
    }

    private @Nullable UUID resolveTargetUuid(CommandActor actor, @Nullable String targetName) {
        if (targetName == null || targetName.isEmpty()) {
            if (CommandUtil.isConsole(actor)) {
                actor.reply(localeManager.getMessage("replay.console_specify_player"));
                return null;
            }
            return actor.uniqueId();
        }

        AbstractPlayer target = platform.getAbstractPlayer(targetName, false);
        if (target == null) {
            actor.reply(localeManager.getMessage("general.player_not_found"));
            return null;
        }
        return target.getUuid();
    }

    private String resolveTargetName(UUID uuid, @Nullable String fallback) {
        AbstractPlayer player = platform.getAbstractPlayer(uuid, false);
        if (player != null) return player.getUsername();
        return fallback != null && !fallback.isEmpty() ? fallback : uuid.toString();
    }

    @FunctionalInterface
    private interface RecordingRequest {
        CompletableFuture<ReplayRecordingResult> send(ReplayService service, UUID target, String targetName);
    }
}
