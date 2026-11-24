package gg.modl.minecraft.core.login;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardedAddressNoticeTest {
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final ForwardedAddressNotice notice = new ForwardedAddressNotice(logger);

    @Test
    void publicAddressesSayNothing() {
        notice.observe("203.0.113.7");
        notice.observe(null);
        notice.observe("not an address");

        assertTrue(logger.warnings().isEmpty());
    }

    @Test
    void theOwnerIsToldOnceThatRealAddressesAreNotForwarded() {
        notice.observe("127.0.0.1");
        notice.observe("172.18.0.4");
        notice.observe("100.64.1.1");

        assertEquals(1, logger.warnings().size());
        assertTrue(logger.warnings().get(0).startsWith(
                "Players are connecting from loopback addresses, so their real IPs are not being forwarded. "
                        + "modl will not link accounts by these addresses."));
    }
}
