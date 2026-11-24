package gg.modl.backend.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class InstalledStorage {
    private final Path root;
    private final Path staging;
    private final Path replaced;
    private final List<Path> movedOut;
    private final List<Path> movedIn;

    InstalledStorage(Path root, Path staging, Path replaced, List<Path> movedOut, List<Path> movedIn) {
        this.root = root;
        this.staging = staging;
        this.replaced = replaced;
        this.movedOut = List.copyOf(movedOut);
        this.movedIn = List.copyOf(movedIn);
    }

    public List<Path> commit() {
        List<Path> leftovers = new ArrayList<>();
        for (Path workspace : List.of(discardReplaced(), staging)) {
            try {
                FileTrees.deleteRecursively(workspace);
            } catch (IOException e) {
                leftovers.add(workspace);
            }
        }
        return List.copyOf(leftovers);
    }

    public void revert() throws IOException {
        try {
            moveNames(movedIn, root, staging);
            moveNames(movedOut, replaced, root);
            Files.deleteIfExists(replaced);
            FileTrees.deleteRecursively(staging);
        } catch (IOException e) {
            throw new IOException("Could not put the previous uploaded files back: they are in " + replaced
                + "; move them back into " + root + " before you start modl", e);
        }
    }

    private Path discardReplaced() {
        String suffix = replaced.getFileName().toString().substring(StorageFolder.REPLACED_PREFIX.length());
        Path discarded = root.resolve(StorageFolder.DISCARDED_PREFIX + suffix);
        try {
            Files.move(replaced, discarded, StandardCopyOption.ATOMIC_MOVE);
            return discarded;
        } catch (IOException e) {
            return replaced;
        }
    }

    private static void moveNames(List<Path> names, Path from, Path to) throws IOException {
        for (Path name : names.reversed()) {
            if (Files.exists(from.resolve(name))) {
                Files.move(from.resolve(name), to.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }
}
