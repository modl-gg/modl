package gg.modl.minecraft.features.reporter;

import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import gg.modl.minecraft.features.reporter.detection.DetectionSource;
import gg.modl.minecraft.features.reporter.detection.ViolationRecord;
import gg.modl.minecraft.features.reporter.detection.ViolationTracker;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class AutoReporter {
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final String TICKET_TYPE = "player";
    private static final String TICKET_PRIORITY = "NORMAL";

    private final PluginLogger logger;
    private final ServerFeaturesConfig config;
    private final TicketCreator ticketCreator;
    private final ViolationTracker violationTracker;
    private final Supplier<String> serverName;
    private final Supplier<String> displayedAnticheatName;

    @Setter private volatile @Nullable ReplayService replayService;

    private final ConcurrentHashMap<UUID, Long> reportCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> reportsInFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean loggedMissingCreator = new AtomicBoolean(false);

    public void checkAndReport(UUID uuid, String playerName, DetectionSource source, String checkName) {
        if (ticketCreator == null) {
            if (loggedMissingCreator.compareAndSet(false, true)) {
                logger.warning("[anticheat] Auto-report skipped: no TicketCreator configured for this platform/mode");
            }
            return;
        }
        ReplayService replays = replayService;
        if (replays != null) replays.recordOnSuspicion(uuid);
        int vl = violationTracker.getViolationCount(uuid, source, checkName);
        int threshold = config.getReportViolationThreshold(checkName);

        if (config.isDebug()) {
            logger.info("[DEBUG] checkAndReport: player=" + playerName
                    + " source=" + source.name() + " check=" + checkName
                    + " vl=" + vl + " threshold=" + threshold);
        }

        if (vl < threshold) return;
        if (isOnCooldown(uuid, playerName)) return;
        if (!tryStartReport(uuid, playerName)) return;

        submitReport(uuid, playerName, source, checkName, vl);
    }

    private boolean isOnCooldown(UUID uuid, String playerName) {
        Long lastReport = reportCooldowns.get(uuid);
        if (lastReport == null) return false;

        long now = System.currentTimeMillis();
        long cooldownMs = config.getReportCooldown() * MILLIS_PER_SECOND;
        long elapsed = now - lastReport;

        if (elapsed >= cooldownMs) return false;

        if (config.isDebug()) {
            long remainingSeconds = (cooldownMs - elapsed) / MILLIS_PER_SECOND;
            logger.info("[DEBUG] Report blocked by cooldown for " + playerName
                    + " (" + remainingSeconds + "s remaining)");
        }
        return true;
    }

    private boolean tryStartReport(UUID uuid, String playerName) {
        boolean alreadyInFlight = reportsInFlight.putIfAbsent(uuid, Boolean.TRUE) != null;
        if (alreadyInFlight && config.isDebug()) {
            logger.info("[DEBUG] Report blocked by pending submission for " + playerName);
        }
        return !alreadyInFlight;
    }

    private void submitReport(UUID uuid, String playerName, DetectionSource source, String checkName, int vl) {
        String anticheatName = displayedAnticheatName.get();
        String uuidStr = uuid.toString();
        String subject = "[" + anticheatName + "] " + checkName + " - " + playerName + " (VL: " + vl + ")";
        String description = buildDescription(uuid, playerName, source, checkName, vl);

        logger.info("Auto-report triggered for " + playerName
                + " (" + source.name() + " " + checkName + " VL: " + vl + ")");

        ReplayService replays = replayService;
        if (replays == null || !replays.shouldAttemptCapture(uuid)) {
            finalizeTicket(uuid, uuidStr, anticheatName, subject, description, playerName, null);
            return;
        }

        CompletableFuture<@Nullable String> replayFuture;
        try {
            replayFuture = replays.captureReplay(uuid, playerName);
        } catch (RuntimeException e) {
            logger.warning("[anticheat] Replay capture failed for auto-report: " + playerName, e);
            finalizeTicket(uuid, uuidStr, anticheatName, subject, description, playerName, null);
            return;
        }

        Futures.whenComplete(replayFuture, (replayId, throwable) -> {
            String reportReplayId = replayId;
            if (throwable != null) {
                logger.warning("[anticheat] Replay capture failed for auto-report: " + playerName, throwable);
                reportReplayId = null;
            }
            if (replayId != null) {
                logger.info("[anticheat] Replay captured for auto-report: " + playerName + " -> " + replayId);
            }
            finalizeTicket(uuid, uuidStr, anticheatName, subject, description, playerName, reportReplayId);
        }, logger);
    }

    private String buildDescription(UUID uuid, String playerName, DetectionSource source, String checkName, int vl) {
        String recentViolations = violationTracker.getRecords(uuid).stream()
                .map(ViolationRecord::toString)
                .collect(Collectors.joining("\n"));
        return "**Automated anticheat report.**\n\n" +
                "**Player:** " + playerName + "\n\n" +
                "**Trigger:** " + source.name() + " " + checkName + " (VL: " + vl + ")\n\n" +
                "**Recent violations:**\n```\n" + recentViolations + "\n```";
    }

    private void finalizeTicket(UUID uuid, String uuidStr, String anticheatName,
                                String subject, String description, String playerName, @Nullable String replayId) {
        try {
            ticketCreator.createTicket(TicketRequest.builder()
                    .creatorUuid(uuidStr)
                    .creatorName(anticheatName)
                    .type(TICKET_TYPE)
                    .subject(subject)
                    .description(description)
                    .reportedPlayerUuid(uuidStr)
                    .reportedPlayerName(playerName)
                    .priority(TICKET_PRIORITY)
                    .createdServer(serverName.get())
                    .replayUrl(replayId)
                    .build());
            markReportSubmitted(uuid);
        } catch (Exception e) {
            logger.warning("[anticheat] Ticket creation failed for auto-report: " + playerName, e);
        } finally {
            reportsInFlight.remove(uuid);
        }
    }

    private void markReportSubmitted(UUID uuid) {
        reportCooldowns.put(uuid, System.currentTimeMillis());
        violationTracker.resetPlayer(uuid);
    }

    public void clearCooldown(UUID uuid) {
        reportCooldowns.remove(uuid);
    }
}
