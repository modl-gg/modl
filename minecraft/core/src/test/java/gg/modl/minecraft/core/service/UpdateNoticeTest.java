package gg.modl.minecraft.core.service;

import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateNoticeTest {
    private static final String RELEASE_PAGE = "https://mirror.example.com/modl/releases/tag/v3.0.2";

    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @Test
    void theNoticeNamesTheReleasePageTheServerSent() {
        new UpdateNotice(logger).announceOnce(startup("3.0.2", RELEASE_PAGE), true);

        assertEquals(2, logger.warnings().size(), logger.warnings().toString());
        assertTrue(logger.warnings().get(1).endsWith(RELEASE_PAGE), logger.warnings().toString());
    }

    @Test
    void aServerThatSendsNoReleaseLeavesTheConsoleQuiet() {
        new UpdateNotice(logger).announceOnce(startup(null, null), true);

        assertTrue(logger.warnings().isEmpty(), logger.warnings().toString());
    }

    @Test
    void controlCharactersFromTheServerNeverReachTheConsole() {
        new UpdateNotice(logger).announceOnce(startup("3.0.2\n\u001b[31mfake", "https://x.example/\n\u001b[0m"), true);
        new UpdateNotice(logger).announceOnce(startup("3.0.2", "https://x.example/\u001b[0m"), true);

        assertEquals(1, logger.warnings().size(), logger.warnings().toString());
        assertTrue(logger.warnings().get(0).startsWith("modl 3.0.2 is available"), logger.warnings().toString());
    }

    private static StartupResponse startup(String latestVersion, String releasePage) {
        return new StartupResponse("https://panel.example.com", null, "srv", false, 1, Collections.<String>emptyList(), false,
                "3.0.1", latestVersion, releasePage);
    }
}
