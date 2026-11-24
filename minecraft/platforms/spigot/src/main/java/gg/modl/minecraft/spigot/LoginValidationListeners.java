package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.util.PluginLogger;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

final class LoginValidationListeners {
    private LoginValidationListeners() {
    }

    static Listener register(JavaPlugin plugin, LoginAdmission admission, PluginLogger logger) {
        Optional<PaperLoginValidation> paper = PaperLoginValidation.detect(admission, logger);
        if (paper.isPresent()) {
            paper.get().registerWith(plugin);
            return paper.get();
        }
        LegacyLoginValidation legacy = new LegacyLoginValidation(admission);
        plugin.getServer().getPluginManager().registerEvents(legacy, plugin);
        return legacy;
    }
}
