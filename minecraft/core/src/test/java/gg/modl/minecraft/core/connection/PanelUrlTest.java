package gg.modl.minecraft.core.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelUrlTest {
    @Test
    void normalizesTheBaseUrl() {
        assertEquals("https://panel.example.com", PanelUrl.of("HTTPS://Panel.Example.com:443/").get());
        assertEquals("http://modl_panel:8080/modl", PanelUrl.of("http://modl_panel:8080/modl/").get());
    }

    @Test
    void buildsEveryPanelLinkFromTheCurrentBase() {
        PanelUrl panelUrl = PanelUrl.of("https://panel.example.com");

        assertEquals("https://panel.example.com/panel/tickets/123456", panelUrl.staffTicket("123456"));
        assertEquals("https://panel.example.com/panel/player/1b2c", panelUrl.playerProfile("1b2c"));
        assertEquals("https://panel.example.com/appeal", panelUrl.appeal());
        assertEquals("https://panel.example.com/replay?id=a%26b", panelUrl.replay("a&b"));
        assertEquals("https://panel.example.com/upload-evidence/tok", panelUrl.evidenceUpload("tok"));
    }

    @Test
    void updatesFollowTheBackendAndReportOnlyRealChanges() {
        PanelUrl panelUrl = PanelUrl.of("http://localhost:8080");

        assertTrue(panelUrl.update("https://panel.example.com/"));
        assertFalse(panelUrl.update("https://panel.example.com"));
        assertFalse(panelUrl.update(""));
        assertFalse(panelUrl.update(null));
        assertFalse(panelUrl.update("not a url"));

        assertEquals("https://panel.example.com/panel/tickets/1", panelUrl.staffTicket("1"));
        assertEquals("https://panel.example.com", panelUrl.get());
    }
}
