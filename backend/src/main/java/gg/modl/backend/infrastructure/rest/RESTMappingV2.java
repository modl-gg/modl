package gg.modl.backend.infrastructure.rest;

public final class RESTMappingV2 {
    public static final String PREFIX = "/v2";
    public static final String PREFIX_MINECRAFT = PREFIX + "/minecraft";
    private static final String PLAYERS = "/players";
    public static final String MINECRAFT_PLAYERS = PREFIX_MINECRAFT + PLAYERS;
    public static final String MINECRAFT_COMPATIBILITY = PREFIX_MINECRAFT + "/compatibility";

    private RESTMappingV2() {
    }
}
