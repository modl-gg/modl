package gg.modl.backend.storage.local;

import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.infrastructure.disk.DurableFiles;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.OwnershipMarker;
import gg.modl.backend.storage.StorageLimitExceededException;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.StoredObjectExistsException;
import gg.modl.backend.storage.UploadTarget;
import gg.modl.backend.storage.local.ObjectMetadataFile.Contents;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

@Slf4j
public class LocalObjectStorage implements ObjectStorage {
    private static final String OBJECTS_PREFIX = RESTMappingV1.STORAGE_OBJECTS + "/";
    private static final String UPLOAD_METHOD = "PUT";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String TEMPORARY_PREFIX = "object-";
    private static final String TEMPORARY_SUFFIX = ".part";
    private static final String LOW_DISK_SPACE_MESSAGE = "The server is running low on disk space";
    private static final int MOVE_ATTEMPTS = 3;

    private final ObjectKeyPaths paths;
    private final UploadSigner signer;
    private final DiskSpaceFloor diskSpaceFloor;
    private final Clock clock;
    private final DirectorySync directorySync;
    private final Set<String> keysBeingWritten = ConcurrentHashMap.newKeySet();

    public LocalObjectStorage(Path root, UploadSigner signer, Clock clock) {
        this(root, signer, DiskSpaceFloor.DEFAULT, clock);
    }

    LocalObjectStorage(Path root, UploadSigner signer, DiskSpaceFloor diskSpaceFloor, Clock clock) {
        this.paths = new ObjectKeyPaths(root);
        this.signer = signer;
        this.diskSpaceFloor = diskSpaceFloor;
        this.clock = clock;
        createDirectories(paths.temporaryDirectory());
        createDirectories(paths.metadataDirectory());
        purgeTemporaryFiles();
        this.directorySync = DirectorySync.probe(paths.root());
    }

    @Override
    public UploadTarget presignUpload(String key, String contentType, long contentLength) {
        requirePath(key);
        Instant expiresAt = clock.instant().plus(UploadTarget.VALIDITY);
        UploadGrant grant = new UploadGrant(key, expiresAt.getEpochSecond(), contentLength, contentType);
        String url = OBJECTS_PREFIX + key
            + "?exp=" + grant.expiresAtEpochSecond()
            + "&len=" + grant.contentLength()
            + "&ct=" + URLEncoder.encode(contentType, StandardCharsets.UTF_8)
            + "&sig=" + signer.sign(grant);
        return new UploadTarget(url, UPLOAD_METHOD, Map.of(CONTENT_TYPE_HEADER, contentType), expiresAt);
    }

    public SignedUploadOutcome acceptSignedUpload(SignedUpload upload) {
        UploadGrant grant = upload.grant();
        if (!isValidUpload(grant, upload.signature())) {
            return SignedUploadOutcome.INVALID_SIGNATURE;
        }
        if (!sameMediaType(upload.requestContentType(), grant.contentType())) {
            return SignedUploadOutcome.CONTENT_TYPE_MISMATCH;
        }
        if (upload.contentLength() < 0) {
            return SignedUploadOutcome.LENGTH_REQUIRED;
        }
        if (grant.exceededBy(upload.contentLength())) {
            return SignedUploadOutcome.TOO_LARGE;
        }
        if (!grant.matches(upload.contentLength())) {
            return SignedUploadOutcome.LENGTH_MISMATCH;
        }
        try {
            write(grant.key(), upload.body(), upload.contentLength(), grant.contentType());
            return SignedUploadOutcome.STORED;
        } catch (StoredObjectExistsException e) {
            return SignedUploadOutcome.ALREADY_EXISTS;
        }
    }

    boolean isValidUpload(UploadGrant grant, String signature) {
        return paths.resolve(grant.key()).isPresent()
            && !grant.isExpired(clock.instant())
            && signer.verify(grant, signature);
    }

    @Override
    public String publicUrl(String key) {
        return OBJECTS_PREFIX + key;
    }

    @Override
    public Optional<String> keyFromPublicUrl(String url) {
        if (url == null) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (uri.getScheme() != null || uri.getRawAuthority() != null || path == null || !path.startsWith(OBJECTS_PREFIX)) {
                return Optional.empty();
            }
            String key = path.substring(OBJECTS_PREFIX.length());
            return paths.resolve(key).map(ignored -> key);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String publicBaseUrl() {
        return "";
    }

    @Override
    public boolean exists(String key) {
        return paths.resolve(key)
            .filter(Files::isRegularFile)
            .flatMap(path -> metadataStoredUnder(key, path))
            .isPresent();
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        return open(key).map(LocalObjectFile::metadata);
    }

    public Optional<LocalObjectFile> open(String key) {
        return paths.resolve(key).flatMap(path -> describe(key, path));
    }

    @Override
    public InputStream read(String key) {
        Path path = paths.resolve(key)
            .filter(resolved -> metadataStoredUnder(key, resolved).isPresent())
            .orElseThrow(LocalObjectStorage::notFound);
        try {
            return Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            throw notFound();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void write(String key, InputStream data, long contentLength, String contentType) {
        Path destination = requirePath(key);
        String caseFoldedKey = key.toLowerCase(Locale.ROOT);
        if (!keysBeingWritten.add(caseFoldedKey)) {
            throw new StoredObjectExistsException();
        }
        try {
            if (Files.exists(destination)) {
                throw new StoredObjectExistsException();
            }
            if (!hasCapacityFor(contentLength)) {
                throw new StorageLimitExceededException(LOW_DISK_SPACE_MESSAGE);
            }
            storeDurably(key, destination, data, contentLength, contentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            keysBeingWritten.remove(caseFoldedKey);
        }
    }

    @Override
    public Set<String> deleteAll(Collection<String> keys) {
        Set<String> failed = new LinkedHashSet<>();
        for (String key : keys) {
            if (!deleteObject(key)) {
                failed.add(key);
            }
        }
        return failed;
    }

    @Override
    public Stream<ListedObject> list(String prefix) {
        Optional<Path> directory = listingDirectory(prefix).filter(Files::isDirectory);
        if (directory.isEmpty()) {
            return Stream.empty();
        }
        return LocalObjectTree.regularFiles(directory.get())
            .flatMap(entry -> ObjectMetadataFile.read(paths.metadataPathOf(entry.path()))
                .map(stored -> listed(stored.key(), entry.attributes()))
                .stream())
            .filter(object -> object.key().startsWith(prefix));
    }

    @Override
    public boolean hasCapacityFor(long bytes) {
        try {
            return diskSpaceFloor.admits(Files.getFileStore(paths.root()), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<String> readOwnershipMarker(String namespace) {
        try {
            return Optional.of(Files.readString(markerPath(namespace), StandardCharsets.UTF_8).trim());
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeOwnershipMarker(String namespace, String owner) {
        Path marker = markerPath(namespace);
        try {
            Path partial = Files.createTempFile(paths.temporaryDirectory(), TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
            try {
                DurableFiles.writeText(partial, owner);
                createDirectoriesDurably(parentOf(marker));
                Files.move(partial, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                forceDirectory(parentOf(marker));
            } finally {
                Files.deleteIfExists(partial);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean deleteObject(String key) {
        Optional<Path> object = paths.resolve(key);
        if (object.isEmpty()) {
            return true;
        }
        Path metadata = paths.metadataPathOf(object.get());
        Optional<Contents> stored = ObjectMetadataFile.read(metadata);
        if (stored.isPresent() && !stored.get().key().equals(key)) {
            return true;
        }
        if (stored.isEmpty() && Files.isRegularFile(object.get())) {
            log.warn("Kept stored object {}: its metadata does not name the key it was stored under", key);
            return false;
        }
        try {
            Files.deleteIfExists(object.get());
            Files.deleteIfExists(metadata);
        } catch (IOException e) {
            log.warn("Failed to delete stored object {}", key, e);
            return false;
        }
        pruneEmptyDirectories(parentOf(object.get()), paths.root());
        pruneEmptyDirectories(parentOf(metadata), paths.metadataDirectory());
        return true;
    }

    private void storeDurably(String key, Path destination, InputStream data, long contentLength, String contentType) throws IOException {
        Path metadata = paths.metadataPathOf(destination);
        Path partialObject = Files.createTempFile(paths.temporaryDirectory(), TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
        Path partialMetadata = Files.createTempFile(paths.temporaryDirectory(), TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
        try {
            if (DurableFiles.copyAtMost(data, partialObject, contentLength) != contentLength) {
                throw new ValidationException("The upload does not match its declared size");
            }
            ObjectMetadataFile.writeDurably(partialMetadata, new Contents(key, contentType));
            moveIntoPlace(partialMetadata, metadata, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try {
                moveIntoPlace(partialObject, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.deleteIfExists(metadata);
                throw e;
            }
            forceDirectory(parentOf(metadata));
            forceDirectory(parentOf(destination));
        } finally {
            Files.deleteIfExists(partialObject);
            Files.deleteIfExists(partialMetadata);
        }
    }

    private void moveIntoPlace(Path source, Path target, StandardCopyOption... options) throws IOException {
        for (int attempt = 1; ; attempt++) {
            createDirectoriesDurably(parentOf(target));
            try {
                Files.move(source, target, options);
                return;
            } catch (NoSuchFileException e) {
                if (attempt == MOVE_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private void createDirectoriesDurably(Path directory) throws IOException {
        Deque<Path> missing = new ArrayDeque<>();
        for (Path current = directory; current != null && !Files.isDirectory(current); current = current.getParent()) {
            missing.push(current);
        }
        while (!missing.isEmpty()) {
            Path created = missing.pop();
            try {
                Files.createDirectory(created);
            } catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(created)) {
                    throw e;
                }
            }
            forceDirectory(parentOf(created));
        }
    }

    private void forceDirectory(Path directory) {
        try {
            directorySync.force(directory);
        } catch (IOException e) {
            log.warn("Could not flush storage directory {} to disk; the change is applied but may not survive a power loss",
                directory, e);
        }
    }

    private static Path parentOf(Path stored) {
        Path parent = stored.getParent();
        if (parent == null) {
            throw new IllegalStateException("Storage path " + stored + " must lie inside the storage directory");
        }
        return parent;
    }

    private void pruneEmptyDirectories(Path start, Path boundary) {
        for (Path current = start; current != null && current.startsWith(boundary) && !current.equals(boundary);
             current = current.getParent()) {
            try {
                Files.delete(current);
            } catch (DirectoryNotEmptyException | NoSuchFileException e) {
                return;
            } catch (IOException e) {
                log.debug("Could not remove empty storage directory {}", current, e);
                return;
            }
        }
    }

    private Optional<Path> listingDirectory(String prefix) {
        String directoryKey = prefix.substring(0, prefix.lastIndexOf('/') + 1);
        if (directoryKey.isEmpty()) {
            return Optional.of(paths.root());
        }
        return paths.resolve(directoryKey.substring(0, directoryKey.length() - 1));
    }

    private Path markerPath(String namespace) {
        return requirePath(namespace).resolve(OwnershipMarker.FILE_NAME);
    }

    private Optional<LocalObjectFile> describe(String key, Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                return Optional.empty();
            }
            return metadataStoredUnder(key, path).map(stored -> new LocalObjectFile(path,
                new StoredObject(key, attributes.size(),
                    Objects.requireNonNullElse(stored.contentType(), StoredObject.DEFAULT_CONTENT_TYPE),
                    attributes.lastModifiedTime().toInstant())));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<Contents> metadataStoredUnder(String key, Path path) {
        return ObjectMetadataFile.read(paths.metadataPathOf(path)).filter(stored -> stored.key().equals(key));
    }

    private Path requirePath(String key) {
        return paths.resolve(key).orElseThrow(() -> new ValidationException("Invalid storage key"));
    }

    private void purgeTemporaryFiles() {
        Path temporaryDirectory = paths.temporaryDirectory();
        try (Stream<Path> leftovers = Files.walk(temporaryDirectory)) {
            List<Path> stale = leftovers
                .filter(path -> !path.equals(temporaryDirectory))
                .sorted(Comparator.reverseOrder())
                .toList();
            for (Path path : stale) {
                Files.deleteIfExists(path);
            }
            if (!stale.isEmpty()) {
                log.info("Removed {} interrupted upload file(s) from {}", stale.size(), temporaryDirectory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not clean the storage temporary directory " + temporaryDirectory, e);
        }
    }

    private static ListedObject listed(String key, BasicFileAttributes attributes) {
        return new ListedObject(key, attributes.size(), attributes.lastModifiedTime().toInstant());
    }

    private static boolean sameMediaType(String requestContentType, String grantedContentType) {
        if (requestContentType == null) {
            return false;
        }
        try {
            return MediaType.parseMediaType(requestContentType).equalsTypeAndSubtype(MediaType.parseMediaType(grantedContentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Object not found");
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the storage directory " + directory, e);
        }
    }
}
