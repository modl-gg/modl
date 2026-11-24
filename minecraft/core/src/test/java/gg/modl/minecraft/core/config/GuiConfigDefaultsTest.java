package gg.modl.minecraft.core.config;

import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiConfigDefaultsTest {
    private static final PluginLogger SILENT = new PluginLogger() {
        @Override public void info(String message) { }
        @Override public void warning(String message) { }
        @Override public void severe(String message) { }
    };

    @TempDir
    Path dataDirectory;

    @Test
    void theReportMenuDefaultsAreThePackagedReportGuiFile() {
        ReportGuiConfig defaults = ReportGuiConfig.createDefault();

        assertEquals("&eQuick Reports", defaults.getInfoConfig().getTitle());
        assertEquals(Arrays.asList("&7/chatreport <player> [details]", "&7/hackreport <player> [details]"),
                defaults.getInfoConfig().getDescription().subList(0, 2));
        assertEquals(8, defaults.getSlots().values().stream().filter(ReportGuiConfig.ReportSlotConfig::isEnabled).count());
        assertTrue(defaults.getSlot(2).isChatReport());
        assertEquals(defaults, ReportGuiConfig.load(dataDirectory, SILENT));
    }

    @Test
    void thePunishMenuDefaultsAreThePackagedPunishGuiFile() {
        PunishGuiConfig defaults = PunishGuiConfig.createDefault();

        assertEquals(12, defaults.getEnabledSlots().size());
        assertEquals(6, defaults.getSlot(1).getOrdinal());
        assertEquals("&eClick to select severity", defaults.getSlot(1).getDescription().get(4));
        assertEquals(defaults, PunishGuiConfig.load(dataDirectory, SILENT));
    }
}
