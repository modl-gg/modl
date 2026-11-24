package gg.modl.backend.database.selection;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.selection.PanelDatabaseChoice.Adoption;
import gg.modl.backend.database.selection.PanelDatabaseChoice.StartEnding;
import gg.modl.backend.infrastructure.disk.DurableFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class DatabaseChoiceFile {
    public static final String FILE_NAME = "database-connection.json";
    public static final String SUPERSEDED_FILE_NAME = "database-connection.superseded.json";

    private static final int FORMAT = 1;
    private static final String TEMPORARY_PREFIX = ".database-connection-";
    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final String POSIX_VIEW = "posix";
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path directory;
    private final Path path;

    public DatabaseChoiceFile(Path dataDirectory) {
        this.directory = dataDirectory.toAbsolutePath().normalize();
        this.path = directory.resolve(FILE_NAME);
    }

    public Path path() {
        return path;
    }

    public Optional<PanelDatabaseChoice> read() throws IOException {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            StoredChoice stored = MAPPER.readValue(Files.readAllBytes(path), StoredChoice.class);
            if (stored.format() != FORMAT) {
                throw new IOException(path + " was written by another modl version (format " + stored.format() + ")");
            }
            return Optional.of(stored.toChoice());
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IOException(path + " is not a valid modl database choice", e);
        }
    }

    public Optional<Path> retire() throws IOException {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Path superseded = directory.resolve(SUPERSEDED_FILE_NAME);
        try {
            Files.move(path, superseded, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(path, superseded, StandardCopyOption.REPLACE_EXISTING);
        }
        DurableFiles.forceDirectory(directory);
        return Optional.of(superseded);
    }

    public void write(PanelDatabaseChoice choice) throws IOException {
        byte[] content;
        try {
            content = MAPPER.writeValueAsBytes(StoredChoice.of(choice));
        } catch (JacksonException e) {
            throw new IOException("The database choice could not be written as JSON", e);
        }
        Files.createDirectories(directory);
        Path temporary = ownerOnlyTemporaryFile(directory);
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveIntoPlace(temporary);
            DurableFiles.forceDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path ownerOnlyTemporaryFile(Path directory) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains(POSIX_VIEW)) {
            return Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        return Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
    }

    private void moveIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record StoredChoice(
        int format,
        StoredConnection selected,
        Adoption adoption,
        @Nullable StoredConnection previous,
        @Nullable StoredLocation copyingTo,
        @Nullable String moveToken,
        @Nullable StoredFailure failure,
        @Nullable StartEnding unreported
    ) {
        static StoredChoice of(PanelDatabaseChoice choice) {
            return new StoredChoice(FORMAT, StoredConnection.of(choice.selected()), choice.adoption(),
                choice.previous() == null ? null : StoredConnection.of(choice.previous()),
                choice.copyingTo() == null ? null : StoredLocation.of(choice.copyingTo()), choice.moveToken(),
                choice.failure() == null ? null : StoredFailure.of(choice.failure()), choice.unreported());
        }

        PanelDatabaseChoice toChoice() {
            if (selected == null || adoption == null) {
                throw new IllegalArgumentException("The database choice names no database");
            }
            return new PanelDatabaseChoice(selected.toConnection(), adoption, previous == null ? null : previous.toConnection(),
                copyingTo == null ? null : copyingTo.toLocation(), moveToken,
                failure == null ? null : failure.toFailedMove(), unreported);
        }
    }

    private record StoredConnection(
        DatabaseDialect dialect,
        String host,
        int port,
        String database,
        boolean tls,
        @Nullable String username,
        @Nullable String password
    ) {
        static StoredConnection of(DatabaseConnection connection) {
            return new StoredConnection(connection.dialect(), connection.host(), connection.port(), connection.database(),
                connection.tls(), connection.username(), connection.password());
        }

        DatabaseConnection toConnection() {
            if (dialect == null || host == null || database == null) {
                throw new IllegalArgumentException("A stored database connection is incomplete");
            }
            return new DatabaseConnection(dialect, host, port, database, tls, username, password);
        }

        @Override
        public String toString() {
            return toConnection().toString();
        }
    }

    private record StoredLocation(DatabaseDialect dialect, String host, int port, String database) {
        static StoredLocation of(DatabaseLocation location) {
            return new StoredLocation(location.dialect(), location.host(), location.port(), location.database());
        }

        DatabaseLocation toLocation() {
            if (dialect == null || host == null || database == null) {
                throw new IllegalArgumentException("A stored database location is incomplete");
            }
            return new DatabaseLocation(dialect, host, port, database);
        }
    }

    private record StoredFailure(StoredLocation target, String code, String message, String detail) {
        static StoredFailure of(FailedMove failed) {
            MoveFailure failure = failed.failure();
            return new StoredFailure(StoredLocation.of(failed.target()), failure.code(), failure.message(), failure.detail());
        }

        FailedMove toFailedMove() {
            if (target == null || code == null || message == null || detail == null) {
                throw new IllegalArgumentException("A stored move failure is incomplete");
            }
            return new FailedMove(target.toLocation(), new MoveFailure(code, message, detail));
        }
    }
}
