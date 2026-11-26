package gg.modl.backend.tunnel.cloudflared;

import java.util.Locale;

public record HostPlatform(String os, String arch) {
    static final String LINUX = "linux";
    static final String WINDOWS = "windows";
    static final String DARWIN = "darwin";
    static final String AMD64 = "amd64";
    static final String ARM64 = "arm64";

    public static HostPlatform current() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static HostPlatform of(String osName, String osArch) {
        return new HostPlatform(os(osName.toLowerCase(Locale.ROOT)), arch(osArch.toLowerCase(Locale.ROOT)));
    }

    public boolean isWindows() {
        return WINDOWS.equals(os);
    }

    public String describe() {
        return os + " " + arch;
    }

    private static String os(String name) {
        if (name.startsWith("linux")) {
            return LINUX;
        }
        if (name.startsWith("windows")) {
            return WINDOWS;
        }
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return DARWIN;
        }
        return name;
    }

    private static String arch(String name) {
        return switch (name) {
            case "amd64", "x86_64", "x64" -> AMD64;
            case "aarch64", "arm64" -> ARM64;
            default -> name;
        };
    }
}
