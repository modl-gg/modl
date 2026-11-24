package gg.modl.backend.update;

public enum Deployment {
    JAR,
    EMBEDDED,
    DOCKER,
    UNMANAGED;

    public boolean managesItsBinary() {
        return this == JAR || this == EMBEDDED;
    }
}
