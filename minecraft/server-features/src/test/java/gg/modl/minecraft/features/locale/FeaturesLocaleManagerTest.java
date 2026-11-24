package gg.modl.minecraft.features.locale;

import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturesLocaleManagerTest {
    private static final PluginLogger LOGGER = PluginLogger.fromJul(Logger.getLogger("test"));

    @TempDir
    Path dataFolder;

    @Test
    void readsTheServerFeaturesSectionOfTheCoreLocale() {
        FeaturesLocaleManager manager = new FeaturesLocaleManager(new LocaleManager());

        assertEquals("§aTeleported to §fAlice§a.",
                manager.getMessage("staff_mode.random_teleport.teleported", Collections.singletonMap("player", "Alice")));
    }

    @Test
    void followsTheConfiguredLocale() throws IOException {
        write("config.yml", "locale: \"de_DE\"");

        FeaturesLocaleManager manager = FeaturesLocaleManager.forDataFolder(dataFolder, LOGGER);

        assertEquals("§aDu wurdest aufgetaut.", manager.getMessage("freeze.unfrozen"));
    }

    @Test
    void anOverrideFileReplacesOneMessageAndReloadsWithIt() throws IOException {
        write("config.yml", "locale: \"en_US\"");
        write("locale/en_US.yml", "server_features:", "  freeze:", "    unfrozen: \"&bYou may move again.\"");
        FeaturesLocaleManager manager = FeaturesLocaleManager.forDataFolder(dataFolder, LOGGER);
        assertEquals("§bYou may move again.", manager.getMessage("freeze.unfrozen"));
        assertEquals("§cTarget is no longer online.", manager.getMessage("staff_mode.teleport.target_offline"));

        write("locale/en_US.yml", "server_features:", "  freeze:", "    unfrozen: \"&eFree.\"");
        manager.reload();

        assertEquals("§eFree.", manager.getMessage("freeze.unfrozen"));
    }

    @Test
    void aMemberReloadRereadsTheConfiguredLocale() throws IOException {
        write("config.yml", "locale: \"en_US\"");
        FeaturesLocaleManager manager = FeaturesLocaleManager.forDataFolder(dataFolder, LOGGER);

        write("config.yml", "locale: \"de_DE\"");
        manager.reload();

        assertEquals("§aDu wurdest aufgetaut.", manager.getMessage("freeze.unfrozen"));
    }

    @Test
    void theStandaloneFeaturesReadTheCoreLocaleSoACoreReloadReachesThem() throws IOException {
        LocaleManager core = new LocaleManager();
        FeaturesLocaleManager manager = new FeaturesLocaleManager(core);
        write("override.yml", "server_features:", "  freeze:", "    unfrozen: \"&eFree.\"");

        core.loadFromFile(dataFolder.resolve("override.yml"));

        assertEquals("§eFree.", manager.getMessage("freeze.unfrozen"));
    }

    @Test
    void aMissingMessageShowsTheMissingLocaleMarker() {
        FeaturesLocaleManager manager = new FeaturesLocaleManager(new LocaleManager());

        assertTrue(manager.getMessage("missing.message", Collections.emptyMap()).contains("server_features.missing.message"));
    }

    private void write(String file, String... lines) throws IOException {
        Path target = dataFolder.resolve(file);
        Files.createDirectories(target.getParent());
        Files.write(target, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }
}
