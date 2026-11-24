package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

public final class ReleaseArtifact {
    public static final long MAX_SIZE = 512L * 1024 * 1024;

    private final ArtifactKind kind;
    private final String file;
    private final String sha256;
    private final long size;

    ReleaseArtifact(ArtifactKind kind, String file, String sha256, long size) {
        this.kind = kind;
        this.file = file;
        this.sha256 = sha256;
        this.size = size;
    }

    static ReleaseArtifact parse(ArtifactKind kind, ReleaseVersion version, JsonFields fields) throws InvalidJsonFieldException {
        String file = fields.text("file");
        String sha256 = fields.text("sha256");
        long size = fields.number("size");
        if (!kind.fileName(version).equals(file)) {
            throw new InvalidJsonFieldException("The " + kind.manifestKey() + " artifact must be named " + kind.fileName(version));
        }
        String problem = problemWith(kind, sha256, size);
        if (problem != null) {
            throw new InvalidJsonFieldException(problem);
        }
        return new ReleaseArtifact(kind, file, sha256, size);
    }

    public static ReleaseArtifact pinned(ArtifactKind kind, ReleaseVersion version, String sha256, long size) {
        String problem = problemWith(kind, sha256, size);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return new ReleaseArtifact(kind, kind.fileName(version), sha256, size);
    }

    private static @Nullable String problemWith(ArtifactKind kind, String sha256, long size) {
        if (!Hex.isSha256(sha256)) {
            return "The " + kind.manifestKey() + " artifact has an invalid sha256";
        }
        if (size < 1 || size > MAX_SIZE) {
            return "The " + kind.manifestKey() + " artifact has an invalid size";
        }
        return null;
    }

    public ArtifactKind kind() {
        return kind;
    }

    public String file() {
        return file;
    }

    public String sha256() {
        return sha256;
    }

    public long size() {
        return size;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReleaseArtifact)) {
            return false;
        }
        ReleaseArtifact artifact = (ReleaseArtifact) other;
        return size == artifact.size && kind == artifact.kind && file.equals(artifact.file) && sha256.equals(artifact.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, file, sha256, size);
    }
}
