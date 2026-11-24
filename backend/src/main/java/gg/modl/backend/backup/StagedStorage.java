package gg.modl.backend.backup;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class StagedStorage implements Closeable {
    private final Path root;
    private final Path staging;
    private final ArchivedStorage summary;
    private boolean installed;

    StagedStorage(Path root, Path staging, ArchivedStorage summary) {
        this.root = root;
        this.staging = staging;
        this.summary = summary;
    }

    public ArchivedStorage summary() {
        return summary;
    }

    public InstalledStorage install() throws IOException {
        Path replaced = Files.createTempDirectory(root, StorageFolder.REPLACED_PREFIX);
        List<Path> movedOut = new ArrayList<>();
        List<Path> movedIn = new ArrayList<>();
        try {
            moveChildren(root, replaced, child -> !StorageFolder.isRestoreWorkspace(child), movedOut);
            moveChildren(staging, root, child -> true, movedIn);
        } catch (IOException e) {
            try {
                new InstalledStorage(root, staging, replaced, movedOut, movedIn).revert();
            } catch (IOException revertFailure) {
                revertFailure.addSuppressed(e);
                throw revertFailure;
            }
            throw new IOException("Could not replace the uploaded files in " + root + "; the uploaded files were not changed", e);
        }
        installed = true;
        return new InstalledStorage(root, staging, replaced, movedOut, movedIn);
    }

    @Override
    public void close() throws IOException {
        if (!installed) {
            FileTrees.deleteRecursively(staging);
        }
    }

    private static void moveChildren(Path from, Path to, Predicate<Path> selected, List<Path> moved) throws IOException {
        for (Path child : FileTrees.children(from)) {
            if (selected.test(child)) {
                Files.move(child, to.resolve(child.getFileName()), StandardCopyOption.ATOMIC_MOVE);
                moved.add(child.getFileName());
            }
        }
    }
}
