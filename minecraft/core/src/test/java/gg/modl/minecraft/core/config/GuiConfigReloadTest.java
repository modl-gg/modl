package gg.modl.minecraft.core.config;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.support.TempConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiConfigReloadTest {
    @TempDir
    Path dataFolder;

    @Test
    void aReloadPicksUpAnEditedReportGuiFile() throws IOException {
        writeReportGui("Cheating");
        ConfigManager guiConfigs = new ConfigManager(dataFolder, new RecordingPluginLogger());
        assertEquals("Cheating", guiConfigs.getReportGuiConfig().getSlot(1).getTitle());

        writeReportGui("Hacking");
        guiConfigs.reloadAll();

        assertEquals("Hacking", guiConfigs.getReportGuiConfig().getSlot(1).getTitle());
    }

    private void writeReportGui(String title) throws IOException {
        TempConfigs.write(dataFolder.resolve("report_gui.yml"),
                "report_gui:",
                "  1:",
                "    enabled: true",
                "    title: \"" + title + "\"",
                "    item: \"minecraft:iron_sword\"");
    }
}
