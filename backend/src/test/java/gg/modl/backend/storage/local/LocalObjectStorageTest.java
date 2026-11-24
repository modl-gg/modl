package gg.modl.backend.storage.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.OwnershipMarker;
import gg.modl.backend.storage.StorageLimitExceededException;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.StoredObjectExistsException;
import gg.modl.backend.storage.UploadTarget;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectStorageTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String KEY = "modl_instance/evidence/PUN-1/0b9f-clip.png";

    @TempDir
    Path root;

    private MutableClock clock;
    private LocalObjectStorage storage;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        storage = new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"), new DiskSpaceFloor(0, 0), clock);
    }

    @Test
    void presignedUploadsCarryAVerifiableSignature() {
        UploadTarget target = storage.presignUpload(KEY, "image/png", 1024);
        Map<String, String> query = query(target.url());
        UploadGrant grant = grantOf(target.url());

        assertThat(target.url()).startsWith("/v1/storage/objects/" + KEY + "?");
        assertThat(target.method()).isEqualTo("PUT");
        assertThat(target.headers()).containsEntry("Content-Type", "image/png");
        assertThat(storage.isValidUpload(grant, query.get("sig"))).isTrue();
    }

    @Test
    void tamperedGrantsAreRejected() {
        UploadTarget target = storage.presignUpload(KEY, "image/png", 1024);
        String signature = query(target.url()).get("sig");
        UploadGrant grant = grantOf(target.url());

        assertThat(storage.isValidUpload(new UploadGrant(grant.key(), grant.expiresAtEpochSecond(), 4096, "image/png"), signature)).isFalse();
        assertThat(storage.isValidUpload(new UploadGrant(grant.key(), grant.expiresAtEpochSecond(), 1024, "text/html"), signature)).isFalse();
        assertThat(storage.isValidUpload(new UploadGrant("modl_instance/evidence/PUN-1/other.png", grant.expiresAtEpochSecond(), 1024,
            "image/png"), signature)).isFalse();
        assertThat(storage.isValidUpload(grant, "not-base64!")).isFalse();
        assertThat(new LocalObjectStorage(root, new UploadSigner(() -> "another-key"), new DiskSpaceFloor(0, 0), clock)
            .isValidUpload(grant, signature)).isFalse();
    }

    @Test
    void expiredGrantsAreRejected() {
        UploadTarget target = storage.presignUpload(KEY, "image/png", 1024);
        clock.advance(Duration.ofMinutes(16));

        assertThat(storage.isValidUpload(grantOf(target.url()), query(target.url()).get("sig"))).isFalse();
    }

    @Test
    void traversalAndReservedKeysAreRejected() {
        List<String> hostileKeys = List.of(
            "../escape.png",
            "/absolute.png",
            "a/../../escape.png",
            "a//b.png",
            "a/./b.png",
            ".tmp/object.part",
            "a/.hidden",
            "a\\b.png",
            ".meta/a/b.png",
            "a/CON",
            "a/nul.txt",
            "a/LPT1.png",
            "a/b.png/",
            ""
        );
        for (String key : hostileKeys) {
            assertThat(storage.exists(key)).as(key).isFalse();
            assertThat(storage.stat(key)).as(key).isEmpty();
            assertThatThrownBy(() -> storage.write(key, bytes("x"), 1, "image/png")).as(key).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> storage.presignUpload(key, "image/png", 1)).as(key).isInstanceOf(ValidationException.class);
        }
        assertThat(Files.exists(root.getParent().resolve("escape.png"))).isFalse();
    }

    @Test
    void writesStoreTheObjectWithItsContentTypeAtomically() throws IOException {
        storage.write(KEY, bytes("hello"), 5, "image/png");

        StoredObject stored = storage.stat(KEY).orElseThrow();
        assertThat(stored.size()).isEqualTo(5);
        assertThat(stored.contentType()).isEqualTo("image/png");
        try (InputStream input = storage.read(KEY)) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        try (Stream<Path> temporaryFiles = Files.list(root.resolve(ObjectKeyPaths.TEMPORARY_DIRECTORY))) {
            assertThat(temporaryFiles).isEmpty();
        }
    }

    @Test
    void bodiesThatDoNotMatchTheDeclaredSizeAreRejected() {
        assertThatThrownBy(() -> storage.write(KEY, bytes("oversized body"), 4, "image/png")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> storage.write(KEY, bytes("tiny"), 10, "image/png")).isInstanceOf(ValidationException.class);
        assertThat(storage.exists(KEY)).isFalse();
    }

    @Test
    void writesAreRefusedBelowTheDiskSpaceFloor() {
        LocalObjectStorage nearlyFull = new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"),
            new DiskSpaceFloor(Long.MAX_VALUE, 0), clock);

        assertThatThrownBy(() -> nearlyFull.write(KEY, bytes("x"), 1, "image/png")).isInstanceOf(StorageLimitExceededException.class);
    }

    @Test
    void listingDeletionAndPublicUrlsRoundTrip() {
        storage.write("modl_instance/tickets/new/a.png", bytes("a"), 1, "image/png");
        storage.write("modl_instance/tickets/new/b.png", bytes("b"), 1, "image/png");
        storage.write("modl_instance/tickets/T-1/c.png", bytes("c"), 1, "image/png");

        try (Stream<ListedObject> listed = storage.list("modl_instance/tickets/new/")) {
            assertThat(listed).extracting(ListedObject::key)
                .containsExactlyInAnyOrder("modl_instance/tickets/new/a.png", "modl_instance/tickets/new/b.png");
        }

        storage.deleteAll(List.of("modl_instance/tickets/new/a.png"));
        assertThat(storage.exists("modl_instance/tickets/new/a.png")).isFalse();

        String url = storage.publicUrl("modl_instance/tickets/T-1/c.png");
        assertThat(url).isEqualTo("/v1/storage/objects/modl_instance/tickets/T-1/c.png");
        assertThat(storage.keyFromPublicUrl(url)).contains("modl_instance/tickets/T-1/c.png");
        assertThat(storage.keyFromPublicUrl("https://elsewhere.example.com" + url)).isEmpty();
        assertThat(storage.keyFromPublicUrl("/v1/storage/objects/../secret")).isEmpty();
        assertThat(storage.publicBaseUrl()).isEmpty();
    }

    @Test
    void existingObjectsAreNeverOverwritten() throws IOException {
        storage.write(KEY, bytes("first"), 5, "image/png");

        assertThatThrownBy(() -> storage.write(KEY, bytes("other"), 5, "image/png")).isInstanceOf(StoredObjectExistsException.class);
        try (InputStream input = storage.read(KEY)) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("first");
        }
    }

    @Test
    void writesToAKeyThatIsADirectoryAreRefused() {
        storage.write("modl_instance/tickets/T-1/a.png", bytes("a"), 1, "image/png");

        assertThatThrownBy(() -> storage.write("modl_instance/tickets/T-1", bytes("x"), 1, "image/png"))
            .isInstanceOf(StoredObjectExistsException.class);
    }

    @Test
    void metadataLivesInAParallelTreeSoKeysCannotCollideWithIt() throws IOException {
        storage.write("modl_instance/tickets/T-1/a.png", bytes("a"), 1, "image/png");
        storage.write("modl_instance/tickets/T-1/a.png.meta", bytes("b"), 1, "text/plain");

        assertThat(root.resolve(ObjectKeyPaths.METADATA_DIRECTORY).resolve("modl_instance/tickets/T-1/a.png")).isRegularFile();
        assertThat(storage.stat("modl_instance/tickets/T-1/a.png").orElseThrow().contentType()).isEqualTo("image/png");
        assertThat(storage.stat("modl_instance/tickets/T-1/a.png.meta").orElseThrow().contentType()).isEqualTo("text/plain");
        try (Stream<ListedObject> listed = storage.list("")) {
            assertThat(listed).extracting(ListedObject::key)
                .containsExactlyInAnyOrder("modl_instance/tickets/T-1/a.png", "modl_instance/tickets/T-1/a.png.meta");
        }

        storage.delete("modl_instance/tickets/T-1/a.png");
        assertThat(root.resolve(ObjectKeyPaths.METADATA_DIRECTORY).resolve("modl_instance/tickets/T-1/a.png")).doesNotExist();
        assertThat(storage.exists("modl_instance/tickets/T-1/a.png.meta")).isTrue();
    }

    @Test
    void interruptedUploadsArePurgedWhenTheStorageOpens() throws IOException {
        Path temporaryDirectory = root.resolve(ObjectKeyPaths.TEMPORARY_DIRECTORY);
        Files.writeString(temporaryDirectory.resolve("object-1.part"), "partial");
        Files.createDirectories(temporaryDirectory.resolve("nested"));
        Files.writeString(temporaryDirectory.resolve("nested/object-2.part"), "partial");

        new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"), new DiskSpaceFloor(0, 0), clock);

        try (Stream<Path> leftovers = Files.list(temporaryDirectory)) {
            assertThat(leftovers).isEmpty();
        }
    }

    @Test
    void capacityFollowsTheDiskSpaceFloor() {
        LocalObjectStorage nearlyFull = new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"),
            new DiskSpaceFloor(Long.MAX_VALUE, 0), clock);

        assertThat(storage.hasCapacityFor(1)).isTrue();
        assertThat(nearlyFull.hasCapacityFor(1)).isFalse();
    }

    @Test
    void directorySyncFallsBackToANoOpWhereDirectoriesCannotBeForced() {
        Path missing = root.resolve("missing");

        assertThatCode(() -> DirectorySync.probe(root).force(root)).doesNotThrowAnyException();
        assertThatCode(() -> DirectorySync.probe(missing).force(missing)).doesNotThrowAnyException();
    }

    @Test
    void deletionPrunesEmptyDirectoriesInBothTrees() {
        storage.write("modl_instance/tickets/T-9/a.png", bytes("a"), 1, "image/png");

        assertThat(storage.deleteAll(List.of("modl_instance/tickets/T-9/a.png"))).isEmpty();

        assertThat(root.resolve("modl_instance/tickets/T-9")).doesNotExist();
        assertThat(root.resolve(ObjectKeyPaths.METADATA_DIRECTORY).resolve("modl_instance/tickets/T-9")).doesNotExist();
        assertThat(root.resolve(ObjectKeyPaths.TEMPORARY_DIRECTORY)).isDirectory();
    }

    @Test
    void deleteAllReportsKeysItCouldNotDeleteAndKeepsGoing() throws IOException {
        storage.write("modl_instance/tickets/T-1/a.png", bytes("a"), 1, "image/png");
        Path blocking = root.resolve("modl_instance/tickets/T-2/b.png");
        Files.createDirectories(blocking.resolve("child"));

        assertThat(storage.deleteAll(List.of("modl_instance/tickets/T-2/b.png", "modl_instance/tickets/T-1/a.png")))
            .containsExactly("modl_instance/tickets/T-2/b.png");
        assertThat(storage.exists("modl_instance/tickets/T-1/a.png")).isFalse();
    }

    @Test
    void listingSkipsEntriesThatCannotBeObjects() throws IOException {
        storage.write("modl_instance/tickets/T-1/a.png", bytes("a"), 1, "image/png");
        storage.writeOwnershipMarker("modl_instance", "owner");
        Files.createDirectories(root.resolve("modl_instance/.hidden"));
        Files.writeString(root.resolve("modl_instance/.hidden/b.png"), "b");

        try (Stream<ListedObject> listed = storage.list("modl_instance/")) {
            assertThat(listed).extracting(ListedObject::key).containsExactly("modl_instance/tickets/T-1/a.png");
        }
        try (Stream<ListedObject> listed = storage.list("missing/")) {
            assertThat(listed).isEmpty();
        }
    }

    @Test
    void ownershipMarkersRoundTripOutsideTheObjectKeySpace() {
        assertThat(storage.readOwnershipMarker("modl_instance")).isEmpty();

        storage.writeOwnershipMarker("modl_instance", "owner-a");
        storage.writeOwnershipMarker("modl_instance", "owner-b");

        assertThat(storage.readOwnershipMarker("modl_instance")).contains("owner-b");
        assertThat(storage.exists("modl_instance/" + OwnershipMarker.FILE_NAME)).isFalse();
    }

    @Test
    void signedUploadsAreAcceptedOnlyForTheExactGrant() {
        UploadTarget target = storage.presignUpload(KEY, "image/png", 4);
        UploadGrant grant = grantOf(target.url());
        String signature = query(target.url()).get("sig");

        assertThat(storage.acceptSignedUpload(new SignedUpload(grant, "bad", "image/png", 4, bytes("abcd"))))
            .isEqualTo(SignedUploadOutcome.INVALID_SIGNATURE);
        assertThat(storage.acceptSignedUpload(new SignedUpload(grant, signature, "text/html", 4, bytes("abcd"))))
            .isEqualTo(SignedUploadOutcome.CONTENT_TYPE_MISMATCH);
        assertThat(storage.acceptSignedUpload(new SignedUpload(grant, signature, "image/png", -1, bytes("abcd"))))
            .isEqualTo(SignedUploadOutcome.LENGTH_REQUIRED);
        assertThat(storage.acceptSignedUpload(new SignedUpload(grant, signature, "image/png", 5, bytes("abcde"))))
            .isEqualTo(SignedUploadOutcome.TOO_LARGE);
        assertThat(storage.acceptSignedUpload(new SignedUpload(grant, signature, "image/png", 3, bytes("abc"))))
            .isEqualTo(SignedUploadOutcome.LENGTH_MISMATCH);
        assertThat(storage.acceptSignedUpload(new SignedUpload(grant, signature, "image/png; name=shot", 4, bytes("abcd"))))
            .isEqualTo(SignedUploadOutcome.STORED);
        assertThat(storage.acceptSignedUpload(new SignedUpload(grant, signature, "image/png", 4, bytes("wxyz"))))
            .isEqualTo(SignedUploadOutcome.ALREADY_EXISTS);
    }

    @Test
    void anotherCaseOfAKeyIsNotThatObject() {
        storage.write(KEY, bytes("clip"), 4, "image/png");

        for (String variant : List.of(KEY.toUpperCase(Locale.ROOT), KEY.toLowerCase(Locale.ROOT), KEY.replace("clip", "Clip"))) {
            assertThat(storage.stat(variant)).as(variant).isEmpty();
            assertThat(storage.exists(variant)).as(variant).isFalse();
            assertThatThrownBy(() -> storage.read(variant)).as(variant).isInstanceOf(ResourceNotFoundException.class);
            assertThat(storage.deleteAll(List.of(variant))).as(variant).isEmpty();
        }
        assertThat(storage.exists(KEY)).isTrue();
    }

    @Test
    void aPathThatAliasesAnotherCaseOnlyAnswersForTheKeyStoredThere() throws IOException {
        String stored = "modl_instance/tickets/T-1/Clip.png";
        String alias = stored.toLowerCase(Locale.ROOT);
        storage.write(stored, bytes("clip"), 4, "image/png");
        moveToPathOf(stored, alias);

        assertThat(storage.stat(alias)).isEmpty();
        assertThat(storage.open(alias)).isEmpty();
        assertThat(storage.exists(alias)).isFalse();
        assertThatThrownBy(() -> storage.read(alias)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> storage.write(alias, bytes("evil"), 4, "text/plain")).isInstanceOf(StoredObjectExistsException.class);
        assertThat(storage.deleteAll(List.of(alias))).isEmpty();
        assertThat(root.resolve(alias)).hasContent("clip");
        try (Stream<ListedObject> listed = storage.list("modl_instance/tickets/")) {
            assertThat(listed).extracting(ListedObject::key).containsExactly(stored);
        }
    }

    @Test
    void aFileWithoutARecordedKeyIsNeverServedListedOrDeleted() throws IOException {
        String key = "modl_instance/tickets/T-1/copied.png";
        Files.createDirectories(root.resolve(key).getParent());
        Files.writeString(root.resolve(key), "copied");

        assertThat(storage.stat(key)).isEmpty();
        assertThat(storage.exists(key)).isFalse();
        assertThat(storage.deleteAll(List.of(key))).containsExactly(key);
        assertThat(root.resolve(key)).hasContent("copied");
        try (Stream<ListedObject> listed = storage.list("modl_instance/")) {
            assertThat(listed).isEmpty();
        }
    }

    private void moveToPathOf(String key, String alias) throws IOException {
        for (Path tree : List.of(root, root.resolve(ObjectKeyPaths.METADATA_DIRECTORY))) {
            Path parked = root.resolve(ObjectKeyPaths.TEMPORARY_DIRECTORY).resolve("parked");
            Files.move(tree.resolve(key), parked);
            Files.createDirectories(tree.resolve(alias).getParent());
            Files.move(parked, tree.resolve(alias));
        }
    }

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static UploadGrant grantOf(String url) {
        Map<String, String> query = query(url);
        String key = URI.create(url).getPath().substring("/v1/storage/objects/".length());
        return new UploadGrant(key, Long.parseLong(query.get("exp")), Long.parseLong(query.get("len")), query.get("ct"));
    }

    private static Map<String, String> query(String url) {
        Map<String, String> parameters = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            parameters.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
