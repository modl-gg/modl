package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.connection.EmbeddedSettings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelPortNoticeTest {
    private static final String CONFIG = "plugins/modl/connection.yml";

    @Test
    void theStartLineNamesTheKeyAndDoesNotClaimThePanelIsReachable() {
        String line = notice(EmbeddedSettings.builder().port(25601).build(), new HashMap<>()).listening();

        assertTrue(line.startsWith("The built-in panel is listening on port 25601."), line);
        assertTrue(line.contains("set embedded.port in " + CONFIG), line);
        assertTrue(line.contains("wiki/Panel-Port-and-Tunnels"), line);
        assertFalse(line.contains("Panel started"), line);
    }

    @Test
    void aGameHostGetsTheAllocationHint() {
        Map<String, String> environment = new HashMap<>();
        environment.put(PanelPortNotice.GAME_HOST_PORT_VARIABLE, "25565");

        String hint = notice(EmbeddedSettings.builder().port(8080).build(), environment).gameHostHint();

        assertTrue(hint.contains("its game port is 25565"), hint);
        assertTrue(hint.contains("Add an allocation for port 8080"), hint);
    }

    @Test
    void aPanelBoundToThisMachineOnlyPointsAtTheTunnel() {
        PanelPortNotice notice = notice(EmbeddedSettings.builder().bindAddress("127.0.0.1")
                .publicUrl("https://panel.example.com").build(), new HashMap<>());

        assertTrue(notice.listening().contains("of this machine only"), notice.listening());
        assertTrue(notice.listening().contains("at https://panel.example.com"), notice.listening());
        assertNull(notice.gameHostHint());
    }

    @Test
    void anOrdinaryMachineGetsNoGameHostHint() {
        assertNull(notice(EmbeddedSettings.builder().build(), new HashMap<>()).gameHostHint());
    }

    private static PanelPortNotice notice(EmbeddedSettings settings, Map<String, String> environment) {
        return new PanelPortNotice(settings, CONFIG, environment::get);
    }
}
