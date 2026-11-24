package gg.modl.backend.storage;

import static gg.modl.backend.database.jooq.Tables.STORAGE_FILES;
import static gg.modl.backend.database.jooq.Tables.STORAGE_RESERVATIONS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.repository.JooqStorageFileRepository;
import gg.modl.backend.storage.repository.JooqStorageReservationRepository;
import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.repository.StorageReservation;
import gg.modl.backend.storage.repository.StorageReservationRepository;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.storage.service.StorageMetadataService;
import gg.modl.backend.storage.service.StoredObjectDeletionListener;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlTestDatabase;
import org.jspecify.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class StorageContractFixture implements AutoCloseable {
    public static final Duration UNATTACHED_TTL = Duration.ofHours(24);
    public static final Instant START = SqlTestDatabase.START;

    private final SqlTestDatabase database;
    private final StorageFileRepository files;
    private final StorageReservationRepository reservations;
    private final ConfirmedStorageTotal total;
    private final ObjectStorage objectStorage = mock(ObjectStorage.class);
    private final StoredObjectDeletionListener listener = mock(StoredObjectDeletionListener.class);
    private final Server server = TestServers.server("server", "admin@example.com");
    private final StorageMetadataService metadata;
    private final StorageDeletionService deletion;

    private StorageContractFixture(SqlTestDatabase database) {
        this.database = database;
        this.files = new JooqStorageFileRepository(database.dsl(), database.dialect(), database.unitOfWork(),
            database.insertIfAbsent());
        this.reservations = new JooqStorageReservationRepository(database.dsl(), database.unitOfWork());
        this.total = new ConfirmedStorageTotal(files);
        this.metadata = new StorageMetadataService(files, reservations, total, database.unitOfWork(), database.clock(), UNATTACHED_TTL);
        this.deletion = new StorageDeletionService(files, reservations, objectStorage, total, database.unitOfWork(), List.of(listener));
        when(objectStorage.deleteAll(anyCollection())).thenReturn(Set.of());
        when(objectStorage.delete(any())).thenReturn(true);
        when(objectStorage.hasCapacityFor(anyLong())).thenReturn(true);
    }

    public static StorageContractFixture start(DialectTarget target, Path directory) {
        return start(target, directory, WriteAttemptHook.NONE);
    }

    public static StorageContractFixture start(DialectTarget target, Path directory, WriteAttemptHook attemptHook) {
        return new StorageContractFixture(SqlTestDatabase.start(target, directory, attemptHook));
    }

    public SqlTestDatabase database() {
        return database;
    }

    public StorageFileRepository files() {
        return files;
    }

    public StorageReservationRepository reservations() {
        return reservations;
    }

    public ConfirmedStorageTotal total() {
        return total;
    }

    public ObjectStorage objectStorage() {
        return objectStorage;
    }

    public StoredObjectDeletionListener listener() {
        return listener;
    }

    public Server server() {
        return server;
    }

    public StorageMetadataService metadata() {
        return metadata;
    }

    public StorageDeletionService deletion() {
        return deletion;
    }

    public void file(String key, long size) {
        file(key, size, START, null);
    }

    public void file(String key, long size, Instant createdAt, @Nullable Instant unattachedExpiresAt) {
        file(key, size, createdAt, unattachedExpiresAt, UploadOrigin.AUTHENTICATED);
    }

    public void file(String key, long size, Instant createdAt, @Nullable Instant unattachedExpiresAt, UploadOrigin origin) {
        files.insertIfAbsent(new StorageFile(key, "file.png", size, "image/png", "other", createdAt), origin, unattachedExpiresAt);
    }

    public void reservation(String key, long bytes) {
        reservation(key, bytes, UploadOrigin.AUTHENTICATED);
    }

    public void reservation(String key, long bytes, UploadOrigin origin) {
        reservations.reserve(new StorageReservation(key, bytes, false, START.plusSeconds(60), START, origin));
    }

    public UploadOrigin fileOriginOf(String key) {
        return UploadOrigin.valueOf(database.dsl().select(STORAGE_FILES.ORIGIN).from(STORAGE_FILES)
            .where(STORAGE_FILES.STORAGE_KEY.eq(key)).fetchSingle(STORAGE_FILES.ORIGIN));
    }

    public boolean hasFile(String key) {
        return database.dsl().fetchExists(STORAGE_FILES, STORAGE_FILES.STORAGE_KEY.eq(key));
    }

    public boolean hasReservation(String key) {
        return database.dsl().fetchExists(STORAGE_RESERVATIONS, STORAGE_RESERVATIONS.STORAGE_KEY.eq(key));
    }

    @Nullable
    public Instant unattachedExpiryOf(String key) {
        return database.dsl().select(STORAGE_FILES.UNATTACHED_EXPIRES_AT)
            .from(STORAGE_FILES)
            .where(STORAGE_FILES.STORAGE_KEY.eq(key))
            .fetchSingle(STORAGE_FILES.UNATTACHED_EXPIRES_AT);
    }

    @Override
    public void close() {
        database.close();
    }
}
