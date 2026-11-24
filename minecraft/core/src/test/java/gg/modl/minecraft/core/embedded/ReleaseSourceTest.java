package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.update.ReleaseVersion;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseSourceTest {
    private static final ReleaseVersion VERSION = ReleaseVersion.parse("3.0.0");

    @Test
    void withoutAMirrorFilesComeFromTheProjectsGithubRelease() {
        assertEquals(PluginInfo.URL + "/releases/download/v3.0.0/modl-embedded-3.0.0.zip",
                ReleaseSource.of(Collections.<String, String>emptyMap()).fileUrl(VERSION, "modl-embedded-3.0.0.zip"));
    }

    @Test
    void aMirrorServesTheSameLayout() {
        ReleaseSource mirror = ReleaseSource.of(Collections.singletonMap(ForwardedSwitches.BASE_URL, "http://127.0.0.1:8000/"));

        assertEquals("http://127.0.0.1:8000/download/v3.0.0/modl-release.json", mirror.fileUrl(VERSION, "modl-release.json"));
    }

    @Test
    void anInvalidMirrorFailsWithTheSettingsToFix() {
        EmbeddedServerException refused = assertThrows(EmbeddedServerException.class,
                () -> ReleaseSource.of(Collections.singletonMap(ForwardedSwitches.BASE_URL, "ftp://mirror.example.com")));

        assertTrue(refused.getMessage().contains("embedded.download-url"), refused.getMessage());
        assertTrue(refused.getMessage().contains(ForwardedSwitches.BASE_URL), refused.getMessage());
    }
}
