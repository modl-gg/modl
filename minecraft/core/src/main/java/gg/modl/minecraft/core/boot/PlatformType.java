package gg.modl.minecraft.core.boot;

public enum PlatformType {
    SPIGOT,
    BUNGEECORD,
    VELOCITY;

    public boolean isProxy() {
        return this != SPIGOT;
    }
}
