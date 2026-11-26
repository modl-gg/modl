package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.service.ActiveRecording;
import gg.modl.minecraft.core.service.RecordingOrigin;
import gg.modl.minecraft.core.service.ReplayMemory;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
final class ReplayRecordingReplies {
    private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;
    private static final String RECORD_FAILED = "replay.record_failed";
    private static final String STATUS_FAILED = "replay.status_failed";

    private final LocaleManager localeManager;

    List<String> started(ReplayRecordingResult result, String player) {
        switch (result.getOutcome()) {
            case OK:
                return line("replay.record_started", player);
            case ALREADY_RECORDING:
                return line("replay.already_recording", player);
            default:
                return failure(result, player, RECORD_FAILED);
        }
    }

    List<String> stopped(ReplayRecordingResult result, String player) {
        switch (result.getOutcome()) {
            case OK:
                return line("replay.record_stopped", player);
            case NOT_STARTED_BY_STAFF:
                return kept(result.getRecording(), player);
            default:
                return failure(result, player, RECORD_FAILED);
        }
    }

    List<String> status(ReplayRecordingResult result, String player) {
        if (result.getOutcome() != ReplayRecordingOutcome.OK) return failure(result, player, STATUS_FAILED);
        List<String> lines = new ArrayList<>();
        ActiveRecording recording = result.getRecording();
        if (recording == null) {
            lines.add(localeManager.getMessage("replay.status_not_recording", mapOf("player", player)));
        } else {
            lines.add(localeManager.getMessage("replay.status_recording", mapOf(
                    "player", player,
                    "origin", origin(recording),
                    "duration", TimeUtil.formatTimeMillis(recording.getRecordingMillis()))));
        }
        ReplayMemory memory = result.getMemory();
        if (memory != null) {
            lines.add(localeManager.getMessage("replay.memory", mapOf(
                    "used", megabytes(memory.getUsedBytes()),
                    "limit", megabytes(memory.getLimitBytes()),
                    "recordings", String.valueOf(memory.getRecordings()))));
        }
        return lines;
    }

    List<String> changeFailed(String player) {
        return line(RECORD_FAILED, player);
    }

    List<String> statusFailed(String player) {
        return line(STATUS_FAILED, player);
    }

    private List<String> kept(@Nullable ActiveRecording recording, String player) {
        if (recording == null) return line("replay.status_not_recording", player);
        return Collections.singletonList(localeManager.getMessage("replay.recording_kept",
                mapOf("player", player, "origin", origin(recording))));
    }

    private List<String> failure(ReplayRecordingResult result, String player, String failedKey) {
        switch (result.getOutcome()) {
            case NOT_RECORDING:
                return line("replay.status_not_recording", player);
            case NOT_LOCAL:
                return line("replay.player_left", player);
            case NO_SERVER_CONNECTED:
                return line("replay.server_unreachable", player);
            case NOT_AVAILABLE:
                return Collections.singletonList(localeManager.getMessage("replay.not_available"));
            default:
                return line(failedKey, player);
        }
    }

    private String origin(ActiveRecording recording) {
        return localeManager.getMessage(originKey(recording.getOrigin()));
    }

    private List<String> line(String key, String player) {
        return Collections.singletonList(localeManager.getMessage(key, mapOf("player", player)));
    }

    private static String originKey(RecordingOrigin origin) {
        switch (origin) {
            case ANTICHEAT_FLAG:
                return "replay.origins.anticheat_flag";
            case STAFF:
                return "replay.origins.staff";
            default:
                return "replay.origins.auto_record";
        }
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_MEGABYTE);
    }
}
