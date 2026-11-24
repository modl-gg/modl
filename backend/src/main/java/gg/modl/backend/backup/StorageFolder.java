package gg.modl.backend.backup;

import gg.modl.backend.storage.local.ObjectKeyPaths;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public final class StorageFolder {
    static final String RESTORE_WORKSPACE_PREFIX = ".modl-restore-";
    static final String REPLACED_PREFIX = RESTORE_WORKSPACE_PREFIX + "replaced-";
    static final String DISCARDED_PREFIX = RESTORE_WORKSPACE_PREFIX + "discarded-";

    private static final String STAGING_PREFIX = RESTORE_WORKSPACE_PREFIX + "staged-";
    private static final Path METADATA_DIRECTORY = Path.of(ObjectKeyPaths.METADATA_DIRECTORY);
    private static final Comparator<StoredFile> EACH_OBJECT_AFTER_ITS_METADATA = Comparator.comparing(StoredFile::objectPath)
        .thenComparing(file -> !file.isMetadata());

    private final Path root;

    public StorageFolder(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public long sizeBytes() throws IOException {
        long bytes = 0;
        for (StoredFile file : files()) {
            bytes += sizeUnlessVanished(file.path());
        }
        return bytes;
    }

    private static long sizeUnlessVanished(Path path) throws IOException {
        try {
            return Files.size(path);
        } catch (NoSuchFileException vanished) {
            return 0;
        }
    }

    public boolean contains(Path path) throws IOException {
        return FileTrees.contains(root, path);
    }

    public StagedStorage stage(StorageArchive archive, BackupDiskSpace diskSpace) throws IOException {
        ArchivedStorage summary = archive.summary()
            .orElseThrow(() -> new IllegalStateException("This backup does not contain uploaded files"));
        Files.createDirectories(root);
        requireNoUnfinishedRestore();
        FileTrees.deleteChildrenStartingWith(root, STAGING_PREFIX);
        FileTrees.deleteChildrenStartingWith(root, DISCARDED_PREFIX);
        diskSpace.require(root, summary.bytes());
        Path staging = Files.createTempDirectory(root, STAGING_PREFIX);
        try {
            archive.extractTo(staging);
            return new StagedStorage(root, staging, summary);
        } catch (IOException | RuntimeException e) {
            FileTrees.deleteQuietly(staging, e);
            throw e;
        }
    }

    static boolean isRestoreWorkspace(Path child) {
        return child.getFileName().toString().startsWith(RESTORE_WORKSPACE_PREFIX);
    }

    List<StoredFile> files() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Path realRoot = root.toRealPath();
        FileCollector collector = new FileCollector(realRoot);
        Files.walkFileTree(realRoot, collector);
        SortedSet<StoredFile> files = new TreeSet<>(EACH_OBJECT_AFTER_ITS_METADATA);
        for (StoredFile file : collector.files) {
            files.add(file);
            if (file.isMetadata()) {
                continue;
            }
            Path metadata = METADATA_DIRECTORY.resolve(file.relativePath());
            if (Files.isRegularFile(realRoot.resolve(metadata), LinkOption.NOFOLLOW_LINKS)) {
                files.add(new StoredFile(realRoot.resolve(metadata), metadata));
            }
        }
        return List.copyOf(files);
    }

    private void requireNoUnfinishedRestore() throws IOException {
        for (Path child : FileTrees.children(root)) {
            if (child.getFileName().toString().startsWith(REPLACED_PREFIX)) {
                throw new IOException("An earlier restore did not finish: the uploaded files it replaced are in " + child
                    + ". Move them back into " + root + ", or delete that folder if you no longer need them, then run restore again");
            }
        }
    }

    record StoredFile(Path path, Path relativePath) {

        boolean isMetadata() {
            return relativePath.getNameCount() > 1 && relativePath.startsWith(METADATA_DIRECTORY);
        }

        Path objectPath() {
            return isMetadata() ? METADATA_DIRECTORY.relativize(relativePath) : relativePath;
        }
    }

    private static final class FileCollector extends SimpleFileVisitor<Path> {
        private final Path realRoot;
        private final List<StoredFile> files = new ArrayList<>();

        private FileCollector(Path realRoot) {
            this.realRoot = realRoot;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            return isExcluded(directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            if (isExcluded(file)) {
                return FileVisitResult.CONTINUE;
            }
            if (attributes.isSymbolicLink()) {
                throw new IOException("Cannot back up " + file + ": the storage folder may not contain symbolic links");
            }
            if (attributes.isRegularFile()) {
                files.add(new StoredFile(file, realRoot.relativize(file)));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
            return continueIfDeletedMeanwhile(failure);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
            return failure == null ? FileVisitResult.CONTINUE : continueIfDeletedMeanwhile(failure);
        }

        private boolean isExcluded(Path path) {
            return realRoot.equals(path.getParent())
                && (isRestoreWorkspace(path) || path.getFileName().toString().equals(ObjectKeyPaths.TEMPORARY_DIRECTORY));
        }

        private static FileVisitResult continueIfDeletedMeanwhile(IOException failure) throws IOException {
            if (failure instanceof NoSuchFileException) {
                return FileVisitResult.CONTINUE;
            }
            throw failure;
        }
    }
}
