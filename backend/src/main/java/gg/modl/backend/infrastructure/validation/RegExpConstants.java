package gg.modl.backend.infrastructure.validation;

public final class RegExpConstants {
    public static final String UUID = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    public static final String MINECRAFT_USERNAME = "^([A-Za-z0-9_]{1,16}|[.*+][A-Za-z0-9_]{1,15})$";

    private RegExpConstants() {
    }
}
