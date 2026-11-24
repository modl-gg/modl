package gg.modl.update;

public final class CompatibilityHeaders {
    public static final String PLUGIN_VERSION = "X-Modl-Plugin-Version";
    public static final String BOOT_ID = "X-Modl-Boot-Id";
    public static final String PLATFORM = "X-Modl-Platform";
    public static final String COMPATIBILITY = "X-Modl-Compatibility";
    public static final String SERVER_VERSION = "X-Modl-Server-Version";
    public static final String VERSION_MISMATCH_CODE = "VERSION_MISMATCH";
    public static final int REFUSAL_STATUS = 409;
    public static final int MAX_BOOT_ID_LENGTH = 36;
    public static final int MAX_PLATFORM_LENGTH = 16;

    private CompatibilityHeaders() {
    }
}
