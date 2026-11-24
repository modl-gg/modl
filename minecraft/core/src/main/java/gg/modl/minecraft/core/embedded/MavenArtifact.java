package gg.modl.minecraft.core.embedded;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.io.IOException;
import java.util.regex.Pattern;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
class MavenArtifact {
    private static final String SEPARATOR = ":";
    private static final String JAR_SUFFIX = ".jar";
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");

    String groupId;
    String artifactId;
    String version;
    String classifier;

    static MavenArtifact parse(String coordinates) throws IOException {
        String[] parts = coordinates.split(SEPARATOR, -1);
        if (parts.length != 3 && parts.length != 4) throw invalid(coordinates);
        for (String part : parts) {
            if (!PART.matcher(part).matches()) throw invalid(coordinates);
        }
        return new MavenArtifact(parts[0], parts[1], parts[2], parts.length == 4 ? parts[3] : "");
    }

    boolean hasClassifier() {
        return !classifier.isEmpty();
    }

    String fileName() {
        return artifactId + "-" + version + (hasClassifier() ? "-" + classifier : "") + JAR_SUFFIX;
    }

    String repositoryPath() {
        return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + fileName();
    }

    @Override
    public String toString() {
        return groupId + SEPARATOR + artifactId + SEPARATOR + version + (hasClassifier() ? SEPARATOR + classifier : "");
    }

    private static IOException invalid(String coordinates) {
        return new IOException("Invalid Maven coordinates in " + ServerLibraryIndex.FILE_NAME + ": " + coordinates);
    }
}
