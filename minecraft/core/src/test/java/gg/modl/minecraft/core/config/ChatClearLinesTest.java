package gg.modl.minecraft.core.config;

import gg.modl.minecraft.core.config.ConfigManager.ChatManagementConfig;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.support.TempConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatClearLinesTest {
    @TempDir
    Path dataFolder;

    @Test
    void aRequestedCountIsCappedAndInvalidInputUsesTheDefault() {
        ChatManagementConfig config = new ChatManagementConfig();

        assertEquals(100, config.linesToClear(null));
        assertEquals(100, config.linesToClear("abc"));
        assertEquals(100, config.linesToClear("0"));
        assertEquals(20, config.linesToClear("20"));
        assertEquals(ChatManagementConfig.MAX_CLEAR_LINES, config.linesToClear("2147483647"));
        assertEquals(100, config.linesToClear("99999999999999"));
    }

    @Test
    void aConfiguredDefaultIsCappedToo() throws IOException {
        TempConfigs.write(dataFolder.resolve("config.yml"), "chat_management:", "  clear_lines: 100000000");

        ConfigManager manager = new ConfigManager(dataFolder, new RecordingPluginLogger());

        assertEquals(ChatManagementConfig.MAX_CLEAR_LINES, manager.getChatManagementConfig().linesToClear(null));
    }
}
