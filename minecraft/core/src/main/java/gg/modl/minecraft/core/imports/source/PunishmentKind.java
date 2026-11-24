package gg.modl.minecraft.core.imports.source;

public enum PunishmentKind {
    BAN,
    MUTE,
    KICK,
    WARNING,
    NOTE;

    public boolean isTimed() {
        return this == BAN || this == MUTE;
    }

    public boolean isNote() {
        return this == WARNING || this == NOTE;
    }
}
