package gg.modl.minecraft.core.network;

public enum MemberActionKind {
    TOGGLE_FREEZE_TARGET,
    TOGGLE_VANISH,
    SELECT_TARGET,
    CLEAR_TARGET,
    OPEN_STAFF_MENU,
    INSPECT_TARGET,
    HACKREPORT_TARGET,
    PROXY_COMMAND,
    STAFF_MODE_REFUSED;

    public boolean isPlayerAction() {
        return this != PROXY_COMMAND;
    }

    public boolean carriesTarget() {
        return this == SELECT_TARGET;
    }
}
