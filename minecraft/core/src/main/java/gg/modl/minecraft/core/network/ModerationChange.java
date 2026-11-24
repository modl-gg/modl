package gg.modl.minecraft.core.network;

public enum ModerationChange {
    CHANGED,
    UNCHANGED,
    LIMIT_REACHED,
    NOT_IN_STAFF_MODE;

    public boolean changed() {
        return this == CHANGED;
    }
}
