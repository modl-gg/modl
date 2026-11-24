package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class UpdateFiles {
    public static final String DIRECTORY = "updates";
    public static final String VERSIONS = "versions";
    public static final String RUN = "run";
    public static final String PENDING = "pending.json";
    public static final String COMMITTED = "committed.json";
    public static final String LAST_FAILURE = "last-failure.json";
    public static final int MAX_STATE_BYTES = 64 * 1024;

    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final Set<PosixFilePermission> OWNER_ONLY =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path root;
    private final JsonMaps json;

    public UpdateFiles(Path dataDirectory, JsonMaps json) {
        this.root = dataDirectory.toAbsolutePath().normalize().resolve(DIRECTORY);
        this.json = json;
    }

    public Path root() {
        return root;
    }

    public Path pendingFile() {
        return root.resolve(PENDING);
    }

    public Path committedFile() {
        return root.resolve(COMMITTED);
    }

    public Path lastFailureFile() {
        return root.resolve(LAST_FAILURE);
    }

    public Path versionDirectory(ReleaseVersion version) {
        return root.resolve(VERSIONS).resolve(version.toString());
    }

    public Path runDirectory() {
        return root.resolve(RUN);
    }

    public String relativeArtifact(ReleaseVersion version, ArtifactKind kind) {
        return VERSIONS + "/" + version + "/" + kind.fileName(version);
    }

    public Path envelope(ReleaseVersion version) {
        return versionDirectory(version).resolve(SignedRelease.ENVELOPE_FILE);
    }

    public Path resolve(String relativeArtifact) throws UpdateStateException {
        Path resolved = root.resolve(relativeArtifact).normalize();
        if (!resolved.startsWith(root.resolve(VERSIONS)) || resolved.equals(root.resolve(VERSIONS))) {
            throw new UpdateStateException(root, "names an artifact outside " + root.resolve(VERSIONS) + ": " + relativeArtifact);
        }
        return resolved;
    }

    public ReleaseVersion versionOf(String relativeArtifact) throws UpdateStateException {
        Path relative = root.resolve(VERSIONS).relativize(resolve(relativeArtifact));
        ReleaseVersion version = relative.getNameCount() == 2 ? ReleaseVersion.tryParse(relative.getName(0).toString()) : null;
        if (version == null) {
            throw new UpdateStateException(root, "names an artifact without a version folder: " + relativeArtifact);
        }
        return version;
    }

    public boolean hasState() {
        return Files.exists(pendingFile()) || Files.exists(committedFile());
    }

    public @Nullable UpdateTransaction readPending() throws UpdateStateException {
        JsonFields fields = read(pendingFile());
        if (fields == null) {
            return null;
        }
        try {
            return UpdateTransaction.fromJson(fields);
        } catch (InvalidJsonFieldException | RuntimeException e) {
            throw new UpdateStateException(pendingFile(), "is damaged (" + e.getMessage() + ")", e);
        }
    }

    public void writePending(UpdateTransaction transaction) throws IOException {
        writeAtomically(pendingFile(), json.write(transaction.toJson()));
    }

    public void deletePending() throws IOException {
        deleteDurably(pendingFile());
    }

    public @Nullable CommittedUpdate readCommitted() throws UpdateStateException {
        JsonFields fields = read(committedFile());
        if (fields == null) {
            return null;
        }
        try {
            return CommittedUpdate.fromJson(fields);
        } catch (InvalidJsonFieldException | RuntimeException e) {
            throw new UpdateStateException(committedFile(), "is damaged (" + e.getMessage() + ")", e);
        }
    }

    public void writeCommitted(CommittedUpdate committed) throws IOException {
        writeAtomically(committedFile(), json.write(committed.toJson()));
    }

    public @Nullable FailedUpdate readLastFailure() throws UpdateStateException {
        JsonFields fields = read(lastFailureFile());
        if (fields == null) {
            return null;
        }
        try {
            return FailedUpdate.fromJson(fields);
        } catch (InvalidJsonFieldException | RuntimeException e) {
            throw new UpdateStateException(lastFailureFile(), "is damaged (" + e.getMessage() + ")", e);
        }
    }

    public void writeLastFailure(FailedUpdate failure) throws IOException {
        writeAtomically(lastFailureFile(), json.write(failure.toJson()));
    }

    public void deleteLastFailure() throws IOException {
        deleteDurably(lastFailureFile());
    }

    public UpdateState readState() throws UpdateStateException {
        UpdateTransaction pending = readPending();
        CommittedUpdate committed = readCommitted();
        return new UpdateState(committed != null && committed.commits(pending) ? null : pending, committed,
            committed != null && committed.commits(pending));
    }

    public void writeAtomically(Path target, byte[] content) throws IOException {
        Path directory = parentOf(target);
        Files.createDirectories(directory);
        Path temporary = directory.resolve(target.getFileName() + TEMPORARY_SUFFIX);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
            restrictToOwner(temporary);
            OutputStream output = Channels.newOutputStream(channel);
            output.write(content);
            output.flush();
            channel.force(true);
        }
        moveAtomically(temporary, target);
        syncDirectory(directory);
    }

    public void deleteVersionsExcept(Set<ReleaseVersion> kept) {
        Path versions = root.resolve(VERSIONS);
        if (!Files.isDirectory(versions)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(versions)) {
            for (Path entry : entries) {
                ReleaseVersion version = ReleaseVersion.tryParse(entry.getFileName().toString());
                if (version == null || !kept.contains(version)) {
                    deleteTree(entry);
                }
            }
        } catch (IOException ignored) {
            return;
        }
    }

    private @Nullable JsonFields read(Path file) throws UpdateStateException {
        byte[] content;
        try (InputStream input = Files.newInputStream(file)) {
            content = readAtMost(input, file);
        } catch (NoSuchFileException e) {
            return null;
        } catch (UpdateStateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateStateException(file, "could not be read (" + e.getMessage() + ")", e);
        }
        try {
            Map<String, Object> values = json.read(content);
            if (values == null) {
                throw new UpdateStateException(file, "is empty");
            }
            return new JsonFields(values, file.getFileName().toString());
        } catch (UpdateStateException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UpdateStateException(file, "is not valid JSON", e);
        }
    }

    private static byte[] readAtMost(InputStream input, Path file) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > MAX_STATE_BYTES) {
                throw new UpdateStateException(file, "is larger than " + MAX_STATE_BYTES + " bytes");
            }
        }
        return buffer.toByteArray();
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteDurably(Path file) throws IOException {
        if (Files.deleteIfExists(file)) {
            syncDirectory(parentOf(file));
        }
    }

    static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
            return;
        }
    }

    private static Path parentOf(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("An update state file needs a parent directory: " + file);
        }
        return parent;
    }

    private static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
            return;
        }
    }

    static void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteTree(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
