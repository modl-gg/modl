package gg.modl.backend.serverkey;

public enum ServerKeyScope {
    FULL,
    BEHIND_PROXY;

    public boolean isMemberOnly() {
        return this == BEHIND_PROXY;
    }
}
