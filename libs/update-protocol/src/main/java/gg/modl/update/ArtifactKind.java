package gg.modl.update;

public enum ArtifactKind {
    SERVER("server", "modl-server-", ".jar"),
    EMBEDDED("embedded", "modl-embedded-", ".zip"),
    PLUGIN("plugin", "modl-", ".jar");

    private final String manifestKey;
    private final String prefix;
    private final String suffix;

    ArtifactKind(String manifestKey, String prefix, String suffix) {
        this.manifestKey = manifestKey;
        this.prefix = prefix;
        this.suffix = suffix;
    }

    public String manifestKey() {
        return manifestKey;
    }

    public String fileName(ReleaseVersion version) {
        return prefix + version + suffix;
    }
}
