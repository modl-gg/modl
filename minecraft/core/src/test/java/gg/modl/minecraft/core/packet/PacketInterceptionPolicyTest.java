package gg.modl.minecraft.core.packet;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketInterceptionPolicyTest {
    private static ProxyPluginRegistry registryOf(String... pluginIds) {
        Set<String> present = new HashSet<>(Arrays.asList(pluginIds));
        return pluginId -> present.contains(pluginId) ? PacketEventsVersion.MINIMUM : null;
    }

    private static ProxyPluginRegistry withPacketEvents(String... pluginIds) {
        String[] withPacketEvents = Arrays.copyOf(pluginIds, pluginIds.length + 1);
        withPacketEvents[pluginIds.length] = PacketInterceptionPolicy.PACKETEVENTS_PLUGIN_ID;
        return registryOf(withPacketEvents);
    }

    @Test
    void autoEnablesOnAProxyWithoutPacketRewritingPlugins() {
        PacketInterceptionDecision decision =
                PacketInterceptionPolicy.decide(PacketInterceptionMode.AUTO, withPacketEvents("luckperms"));

        assertTrue(decision.isEnabled());
    }

    @Test
    void interceptionIsOffWhenPacketEventsIsNotInstalled() {
        PacketInterceptionDecision decision =
                PacketInterceptionPolicy.decide(PacketInterceptionMode.AUTO, registryOf("luckperms"));

        assertFalse(decision.isEnabled());
        assertTrue(decision.getConflicts().isEmpty());
    }

    @Test
    void anOutdatedPacketEventsTurnsInterceptionOff() {
        PacketInterceptionDecision decision = PacketInterceptionPolicy.decide(PacketInterceptionMode.ENABLED,
                pluginId -> PacketInterceptionPolicy.PACKETEVENTS_PLUGIN_ID.equals(pluginId) ? "2.7.0" : null);

        assertFalse(decision.isEnabled());
        assertTrue(decision.getReason().contains("2.14.0"));
    }

    @Test
    void enabledCannotOverrideAMissingPacketEvents() {
        PacketInterceptionDecision decision =
                PacketInterceptionPolicy.decide(PacketInterceptionMode.ENABLED, registryOf());

        assertFalse(decision.isEnabled());
    }

    @Test
    void missingPacketEventsIsReportedAsInformation() {
        RecordingPluginLogger logger = new RecordingPluginLogger();

        PacketInterceptionPolicy.decide(PacketInterceptionMode.AUTO, registryOf()).report(logger);

        assertEquals(1, logger.infos().size());
        assertTrue(logger.warnings().isEmpty());
    }

    @Test
    void autoDisablesWhenLimboApiIsInstalled() {
        PacketInterceptionDecision decision =
                PacketInterceptionPolicy.decide(PacketInterceptionMode.AUTO, withPacketEvents("limboapi"));

        assertFalse(decision.isEnabled());
        assertEquals(1, decision.getConflicts().size());
        assertEquals("limboapi", decision.getConflicts().get(0).getPluginId());
        assertTrue(decision.getReason().contains(PacketInterceptionPolicy.CONFIG_KEY));
    }

    @Test
    void conflictsAreReportedAsWarnings() {
        RecordingPluginLogger logger = new RecordingPluginLogger();

        PacketInterceptionPolicy.decide(PacketInterceptionMode.AUTO, withPacketEvents("limboapi")).report(logger);

        assertEquals(1, logger.warnings().size());
        assertTrue(logger.infos().isEmpty());
    }

    @Test
    void autoNamesEveryDetectedConflict() {
        PacketInterceptionDecision decision = PacketInterceptionPolicy.decide(
                PacketInterceptionMode.AUTO, withPacketEvents("limboapi", "eaglerxserver"));

        assertFalse(decision.isEnabled());
        assertEquals(2, decision.getConflicts().size());
    }

    @Test
    void enabledOverridesADetectedConflict() {
        PacketInterceptionDecision decision =
                PacketInterceptionPolicy.decide(PacketInterceptionMode.ENABLED, withPacketEvents("limboapi"));

        assertTrue(decision.isEnabled());
        assertEquals(1, decision.getConflicts().size());
    }

    @Test
    void disabledWinsEvenWithoutConflicts() {
        PacketInterceptionDecision decision = PacketInterceptionPolicy.decide(
                PacketInterceptionMode.DISABLED, withPacketEvents());

        assertFalse(decision.isEnabled());
        assertTrue(decision.getConflicts().isEmpty());
    }

    @Test
    void unreadableConfigValuesFallBackToAuto() {
        assertEquals(PacketInterceptionMode.AUTO, PacketInterceptionMode.fromConfig(null));
        assertEquals(PacketInterceptionMode.AUTO, PacketInterceptionMode.fromConfig(Collections.emptyList()));
        assertEquals(PacketInterceptionMode.AUTO, PacketInterceptionMode.fromConfig("nonsense"));
        assertEquals(PacketInterceptionMode.ENABLED, PacketInterceptionMode.fromConfig(" Enabled "));
        assertEquals(PacketInterceptionMode.DISABLED, PacketInterceptionMode.fromConfig("DISABLED"));
    }
}
