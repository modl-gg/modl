package gg.modl.backend.storage.local;

import gg.modl.backend.storage.StorageKeySegments;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ObjectKeyPaths {
    public static final String TEMPORARY_DIRECTORY = ".tmp";
    public static final String METADATA_DIRECTORY = ".meta";

    private static final int MAX_KEY_LENGTH = 512;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,254}");

    private final Path root;
    private final Path metadataRoot;

    ObjectKeyPaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.metadataRoot = this.root.resolve(METADATA_DIRECTORY);
    }

    Path root() {
        return root;
    }

    Path temporaryDirectory() {
        return root.resolve(TEMPORARY_DIRECTORY);
    }

    Path metadataDirectory() {
        return metadataRoot;
    }

    Optional<Path> resolve(String key) {
        if (!isValidKey(key)) {
            return Optional.empty();
        }
        Path path = root.resolve(key).normalize();
        return path.startsWith(root) && !path.equals(root) ? Optional.of(path) : Optional.empty();
    }

    Path metadataPathOf(Path objectPath) {
        return metadataRoot.resolve(root.relativize(objectPath));
    }

    public static boolean isValidKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        for (String segment : key.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || StorageKeySegments.isWindowsDeviceName(segment)) {
                return false;
            }
        }
        return true;
    }
}
