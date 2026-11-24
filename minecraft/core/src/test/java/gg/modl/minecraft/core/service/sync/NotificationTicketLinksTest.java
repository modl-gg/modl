package gg.modl.minecraft.core.service.sync;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NotificationTicketLinksTest {
    @Test
    void playerLinksUseTheBackendProvidedPublicUrl() {
        Map<String, Object> data = new HashMap<>();
        data.put("ticketId", "123456");
        data.put("ticketUrl", "https://panel.example.com/ticket/123456?key=abc");

        assertEquals("https://panel.example.com/ticket/123456?key=abc", NotificationService.playerTicketUrl(data));
    }

    @Test
    void playersGetNoLinkWithoutAnAbsoluteBackendUrl() {
        Map<String, Object> relative = new HashMap<>();
        relative.put("ticketUrl", "/ticket/123456");
        Map<String, Object> idOnly = new HashMap<>();
        idOnly.put("ticketId", "123456");

        assertNull(NotificationService.playerTicketUrl(relative));
        assertNull(NotificationService.playerTicketUrl(idOnly));
    }
}
