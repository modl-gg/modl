package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.TransferListener;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadProgressTest {
    private static final ServerLibrary SPRING = new ServerLibrary("spring-core-7.1.0.jar", "a".repeat(64), 2_000_000);
    private static final ServerLibrary NETTY = new ServerLibrary("netty-common-4.2.20.Final.jar", "b".repeat(64), 1_500_000);

    private final AtomicLong now = new AtomicLong();
    private final StartupProgress startup = new StartupProgress(now::get);
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final DownloadProgress progress = new DownloadProgress(logger, startup);

    @Test
    void announcesTheDownloadOnceAndReportsProgressAtMostEveryFiveSeconds() {
        progress.begin("3.0.0", 2, SPRING.getSize() + NETTY.getSize());
        TransferListener spring = progress.listenerFor(SPRING);

        spring.transferred(500_000);
        advanceSeconds(4);
        spring.transferred(1_000_000);
        advanceSeconds(1);
        spring.transferred(1_500_000);
        spring.transferred(1_600_000);
        advanceSeconds(5);
        progress.completed(SPRING);

        assertEquals(Arrays.asList(
                "Downloading modl server 3.0.0 libraries from Maven Central: 2 files, 3.5 MB.",
                "Downloading modl server libraries: 0 of 2 files, 1.5 of 3.5 MB.",
                "Downloading modl server libraries: 1 of 2 files, 2.0 of 3.5 MB."
        ), logger.infos());
    }

    @Test
    void everyTransferCountsAsStartupProgress() {
        progress.begin("3.0.0", 1, NETTY.getSize());
        advanceSeconds(100);

        progress.listenerFor(NETTY).transferred(1);

        assertEquals(0, startup.idleNanos());
    }

    @Test
    void aRestartedAttemptReplacesTheBytesOfTheFailedOne() {
        progress.begin("3.0.0", 1, NETTY.getSize());
        TransferListener netty = progress.listenerFor(NETTY);
        netty.transferred(1_200_000);
        netty.transferred(0);
        netty.transferred(300_000);
        advanceSeconds(5);
        netty.transferred(400_000);

        assertTrue(logger.infos().contains("Downloading modl server libraries: 0 of 1 files, 0.4 of 1.5 MB."), logger.infos().toString());
    }

    @Test
    void summarisesTheCompletedDownload() {
        progress.begin("3.0.0", 2, SPRING.getSize() + NETTY.getSize());
        progress.completed(SPRING);
        progress.completed(NETTY);
        advanceSeconds(21);

        assertEquals("downloaded 2 files, 3.5 MB, in 21s", progress.summary());
    }

    private void advanceSeconds(long seconds) {
        now.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }
}
