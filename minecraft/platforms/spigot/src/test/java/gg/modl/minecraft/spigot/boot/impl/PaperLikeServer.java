package gg.modl.minecraft.spigot.boot.impl;

import gg.modl.minecraft.spigot.boot.api.LegacyPaperSpigotApi;
import gg.modl.minecraft.spigot.boot.api.PaperLikeSpigotApi;
import gg.modl.minecraft.spigot.boot.api.SpigotOnlyApi;
import org.bukkit.configuration.file.YamlConfiguration;

public final class PaperLikeServer {
    private static final String BUNGEECORD_KEY = "settings.bungeecord";

    private PaperLikeServer() {
    }

    public static PaperLikeSpigotApi spigotWithVelocityForwarding(boolean enabled) {
        return paper(enabled, false);
    }

    public static PaperLikeSpigotApi paper(boolean velocity, boolean bungeeCord) {
        return new PaperLikeSpigotApi() {
            @Override
            public YamlConfiguration getConfig() {
                return spigotYml(bungeeCord);
            }

            @Override
            public YamlConfiguration getSpigotConfig() {
                return spigotYml(bungeeCord);
            }

            @Override
            public YamlConfiguration getPaperConfig() {
                YamlConfiguration config = new YamlConfiguration();
                config.set("proxies.velocity.enabled", velocity);
                return config;
            }
        };
    }

    public static LegacyPaperSpigotApi paperSpigot188(boolean bungeeCord) {
        return new LegacyPaperSpigotApi() {
            @Override
            public YamlConfiguration getConfig() {
                YamlConfiguration bukkitYml = new YamlConfiguration();
                bukkitYml.set("settings.allow-end", true);
                bukkitYml.set("settings.connection-throttle", 4000);
                return bukkitYml;
            }

            @Override
            public YamlConfiguration getSpigotConfig() {
                return spigotYml(bungeeCord);
            }
        };
    }

    public static LegacyPaperSpigotApi paperSpigot188BeforeSpigotYmlIsLoaded() {
        return new LegacyPaperSpigotApi() {
            @Override
            public YamlConfiguration getSpigotConfig() {
                return null;
            }
        };
    }

    public static SpigotOnlyApi spigot(boolean bungeeCord) {
        return new SpigotOnlyApi() {
            @Override
            public YamlConfiguration getConfig() {
                return spigotYml(bungeeCord);
            }
        };
    }

    private static YamlConfiguration spigotYml(boolean bungeeCord) {
        YamlConfiguration spigotYml = new YamlConfiguration();
        spigotYml.set("config-version", 8);
        spigotYml.set(BUNGEECORD_KEY, bungeeCord);
        spigotYml.set("settings.timeout-time", 60);
        return spigotYml;
    }
}
