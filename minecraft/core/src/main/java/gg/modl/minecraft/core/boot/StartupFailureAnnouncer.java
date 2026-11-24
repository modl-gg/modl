package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.Scheduling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class StartupFailureAnnouncer {
    static final long REPEAT_INTERVAL_SECONDS = 60;
    private static final String RULE = "====================================================================";

    private final PluginLogger logger;
    private final String reason;
    private final String remedy;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modl-startup-failure");
        thread.setDaemon(true);
        return thread;
    });

    private StartupFailureAnnouncer(PluginLogger logger, String reason, String remedy) {
        this.logger = logger;
        this.reason = reason;
        this.remedy = remedy;
    }

    static StartupFailureAnnouncer start(PluginLogger logger, String reason, String remedy) {
        StartupFailureAnnouncer announcer = new StartupFailureAnnouncer(logger, reason, remedy);
        announcer.announce();
        Scheduling.atFixedRate(announcer.executor, announcer::announce, REPEAT_INTERVAL_SECONDS, REPEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS, logger);
        return announcer;
    }

    void stop() {
        executor.shutdownNow();
    }

    private void announce() {
        logger.severe(RULE);
        logger.severe("modl is not running: " + reason);
        logger.severe(remedy);
        logger.severe("Players cannot join until this is fixed.");
        logger.severe(RULE);
    }
}
