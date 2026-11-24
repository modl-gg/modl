package gg.modl.minecraft.features.reporter.hook;

public interface AntiCheatHook {

    String getName();
    boolean isAvailable();
    void register();
    void unregister();
}
