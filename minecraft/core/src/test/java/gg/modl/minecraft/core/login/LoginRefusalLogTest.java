package gg.modl.minecraft.core.login;

import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRefusalLogTest {
    @Test
    void aRefusedLoginIsOneDebugLineWithoutTheKickScreen() {
        RecordingLogger logger = new RecordingLogger();

        LoginRefusalLog.refused(logger, "Steve",
                "§c§lYou are permanently banned\n§fReason: §7Cheating\n§x§a§b§c§d§e§fAppeal at https://panel.example.com/appeal");

        assertEquals(1, logger.debug.size());
        assertEquals("Login blocked for Steve: You are permanently banned", logger.debug.get(0));
        assertTrue(logger.loud.isEmpty());
    }

    @Test
    void aRefusalStartingWithABlankLineSummarizesItsFirstText() {
        assertEquals("Please re-log momentarily.", LoginRefusalLog.summary("\n§cPlease re-log momentarily.\n§7More"));
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> debug = new ArrayList<>();
        private final List<String> loud = new ArrayList<>();

        @Override public void info(String message) { loud.add(message); }
        @Override public void warning(String message) { loud.add(message); }
        @Override public void severe(String message) { loud.add(message); }
        @Override public void debug(String message) { debug.add(message); }
    }
}
