package gg.modl.minecraft.core.boot;

public final class RunningNotice {
    private RunningNotice() {
    }

    public static String of(String platformName) {
        return "modl is running (" + platformName + ").";
    }

    public static String behindProxy() {
        return of("Spigot, behind-proxy mode");
    }
}
