package gg.modl.minecraft.core.imports.source;

public enum PunishmentScope {
    ACCOUNT,
    IP_WIDE,
    SERVER_SCOPED;

    public boolean isScoped() {
        return this != ACCOUNT;
    }
}
