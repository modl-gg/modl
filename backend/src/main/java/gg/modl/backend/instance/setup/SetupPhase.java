package gg.modl.backend.instance.setup;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupProgress;

public enum SetupPhase {
    UNCLAIMED,
    OWNER_SIGN_IN,
    READY;

    public static SetupPhase of(Server server) {
        if (!server.isClaimed()) {
            return UNCLAIMED;
        }
        SetupProgress setup = server.getSetup();
        return setup != null && !setup.ownerHasSignedIn() ? OWNER_SIGN_IN : READY;
    }

    public boolean needsCode() {
        return this != READY;
    }

    public SetupCodeKind codeKind() {
        return this == OWNER_SIGN_IN ? SetupCodeKind.OWNER_SIGN_IN : SetupCodeKind.SETUP;
    }
}
