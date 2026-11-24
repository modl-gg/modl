package gg.modl.minecraft.core.connection;

public enum ProxyForwarding {
    NONE(""),
    BUNGEECORD("spigot.yml settings.bungeecord"),
    VELOCITY("Paper proxies.velocity.enabled");

    private final String source;

    ProxyForwarding(String source) {
        this.source = source;
    }

    public boolean isEnabled() {
        return this != NONE;
    }

    public boolean forwardsIdentitiesVerifiedByASharedSecret() {
        return this == VELOCITY;
    }

    public String source() {
        return source;
    }
}
