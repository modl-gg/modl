package gg.modl.minecraft.spigot.features;

import org.bukkit.GameMode;

final class ReplayGameMode {
    private static final String SPECTATOR = "SPECTATOR";

    private ReplayGameMode() {
    }

    static int of(GameMode mode) {
        switch (mode) {
            case CREATIVE: return 2;
            case ADVENTURE: return 3;
            case SURVIVAL: return 1;
            default: return SPECTATOR.equals(mode.name()) ? 4 : 1;
        }
    }
}
