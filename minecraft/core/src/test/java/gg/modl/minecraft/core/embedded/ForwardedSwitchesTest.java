package gg.modl.minecraft.core.embedded;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardedSwitchesTest {
    private final Map<String, String> environment = new HashMap<>();
    private final Map<String, String> properties = new HashMap<>();

    @Test
    void forwardsExactlyTheForwardedSwitchesAndASystemPropertyWins() {
        for (String name : ForwardedSwitches.FORWARDED) environment.put(name, " env ");
        environment.put("MODL_DATABASE_PASSWORD", "never forwarded");
        properties.put("MODL_ALLOW_DOWNGRADE", "true");

        Map<String, String> switches = ForwardedSwitches.forwarded(properties::get, environment::get, true, "");

        Map<String, String> expected = new HashMap<>();
        for (String name : ForwardedSwitches.FORWARDED) expected.put(name, "env");
        expected.put("MODL_ALLOW_DOWNGRADE", "true");
        assertEquals(expected, switches);
    }

    @Test
    void aReleaseMirrorReachesTheEmbeddedServerWithItsReleaseList() {
        environment.put("MODL_UPDATE_BASE_URL", "https://mirror.example.com/releases");
        environment.put("MODL_UPDATE_API_URL", "https://mirror.example.com/api/releases");

        Map<String, String> switches = ForwardedSwitches.forwarded(properties::get, environment::get, true, "");

        assertEquals("https://mirror.example.com/api/releases", switches.get("MODL_UPDATE_API_URL"));
        assertEquals("https://mirror.example.com/releases", switches.get("MODL_UPDATE_BASE_URL"));
    }

    @Test
    void theDisabledUpdateCheckerTurnsChecksOffWhateverTheHostSays() {
        environment.put(ForwardedSwitches.UPDATE_CHECK, "true");

        Map<String, String> switches = ForwardedSwitches.forwarded(properties::get, environment::get, false, "");

        assertEquals("false", switches.get(ForwardedSwitches.UPDATE_CHECK));
    }

    @Test
    void nothingSetForwardsNothing() {
        assertTrue(ForwardedSwitches.forwarded(properties::get, environment::get, true, "").isEmpty());
    }

    @Test
    void theConfiguredDownloadMirrorBecomesTheReleaseBaseOfTheEmbeddedServer() {
        Map<String, String> switches = ForwardedSwitches.forwarded(properties::get, environment::get, true,
                " https://mirror.example.com/releases ");

        assertEquals("https://mirror.example.com/releases", switches.get(ForwardedSwitches.BASE_URL));
    }

    @Test
    void aHostReleaseBaseWinsOverTheConfiguredDownloadMirror() {
        environment.put(ForwardedSwitches.BASE_URL, "https://host.example.com/releases");

        Map<String, String> switches = ForwardedSwitches.forwarded(properties::get, environment::get, true,
                "https://mirror.example.com/releases");

        assertEquals("https://host.example.com/releases", switches.get(ForwardedSwitches.BASE_URL));
    }
}
