package gg.modl.minecraft.core.impl.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModlPanelCommandTest {
    @Test
    void roundsLoginLinkExpiryUpToWholeMinutes() {
        assertEquals(2, ModlPanelCommand.expiryMinutes(120));
        assertEquals(2, ModlPanelCommand.expiryMinutes(61));
        assertEquals(1, ModlPanelCommand.expiryMinutes(0));
    }

    @Test
    void mapsLinkFailuresToMessages() {
        assertEquals("panel_account.link_invalid_code", ModlPanelCommand.linkFailureKey(400));
        assertEquals("panel_account.rate_limited", ModlPanelCommand.linkFailureKey(429));
        assertEquals("panel_account.link_already_linked", ModlPanelCommand.linkFailureKey(409));
        assertEquals("panel_account.link_error", ModlPanelCommand.linkFailureKey(0));
    }

    @Test
    void mapsLoginLinkFailuresToMessages() {
        assertEquals("panel_account.login_not_linked", ModlPanelCommand.loginFailureKey(404));
        assertEquals("panel_account.login_not_online", ModlPanelCommand.loginFailureKey(409));
        assertEquals("panel_account.rate_limited", ModlPanelCommand.loginFailureKey(429));
        assertEquals("panel_account.login_error", ModlPanelCommand.loginFailureKey(500));
    }
}
