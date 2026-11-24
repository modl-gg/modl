package gg.modl.backend.migration.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RetainedIpHistoryReleaseWorker {
    private final RetainedIpHistoryRelease release;
    private volatile boolean shuttingDown;

    @EventListener(ContextClosedEvent.class)
    public void stopForShutdown() {
        shuttingDown = true;
    }

    @Async("migrationTaskExecutor")
    public void releaseInBackground() {
        try {
            release.releaseWhile(() -> !shuttingDown);
        } catch (RuntimeException e) {
            log.warn("Releasing imported IP history was interrupted and continues with the next retention sweep", e);
        }
    }
}
