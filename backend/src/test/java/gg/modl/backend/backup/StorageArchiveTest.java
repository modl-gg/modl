package gg.modl.backend.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.infrastructure.disk.FakeFileStore;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.UploadSigner;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageArchiveTest {
    private static final BackupDiskSpace UNLIMITED_SPACE =
        new BackupDiskSpace(new DiskSpaceFloor(0, 0), path -> new FakeFileStore(Long.MAX_VALUE, Long.MAX_VALUE / 2));

    @TempDir
    Path directory;

    @Test
    void uploadedFilesRoundTripAndReplaceTheTargetFolder() throws IOException {
        Path source = directory.resolve("source");
        write(source.resolve("modl_instance/tickets/a.png"), "image");
        write(source.resolve(".meta/modl_instance/tickets/a.png"), "{\"contentType\":\"image/png\"}");
        write(source.resolve("modl_instance/.modl-instance"), "owner");
        write(source.resolve(".modl-restore-staged-1/stale.bin"), "left over by a crashed restore");
        Path archive = directory.resolve("backup.zip");

        ArchivedStorage written = writeArchive(archive, new StorageFolder(source));

        assertThat(written).isEqualTo(new ArchivedStorage(3, 5 + 27 + 5));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertThat(zip.getEntry("storage.files/modl_instance/tickets/a.png")).isNotNull();
            assertThat(zip.stream().map(ZipEntry::getName)).noneMatch(name -> name.contains(".modl-restore-"));
        }

        Path target = directory.resolve("target");
        write(target.resolve("modl_instance/tickets/b.png"), "replaced");
        write(target.resolve(".modl-restore-staged-9/old.bin"), "left over by a crashed restore");
        write(target.resolve(".modl-restore-discarded-8/old.bin"), "left over by a finished restore");
        try (StorageArchive storage = StorageArchive.open(archive)) {
            assertThat(storage.summary()).contains(written);
            try (StagedStorage staged = new StorageFolder(target).stage(storage, UNLIMITED_SPACE)) {
                assertThat(staged.install().commit()).isEmpty();
            }
        }

        assertThat(contents(target)).isEqualTo(Map.of(
            "modl_instance/tickets/a.png", "image",
            ".meta/modl_instance/tickets/a.png", "{\"contentType\":\"image/png\"}",
            "modl_instance/.modl-instance", "owner"));
        try (Stream<Path> children = Files.list(target)) {
            assertThat(children.map(path -> path.getFileName().toString())).noneMatch(name -> name.startsWith(".modl-restore-"));
        }
    }

    @Test
    void aRestoredObjectIsServedListedAndDeletable() throws IOException {
        Path source = directory.resolve("source");
        String evidence = "instance/evidence/Pun-1/Clip.png";
        String replay = "instance/replays/replay-1.modlreplay";
        LocalObjectStorage original = localStorage(source);
        store(original, evidence, "image", "image/png");
        store(original, replay, "replay", "application/octet-stream");
        original.writeOwnershipMarker("instance", "owner");
        write(source.resolve(".tmp/object-1.part"), "upload in progress");
        Path archive = directory.resolve("backup.zip");
        writeArchive(archive, new StorageFolder(source));

        Path target = directory.resolve("target");
        try (StorageArchive storage = StorageArchive.open(archive);
             StagedStorage staged = new StorageFolder(target).stage(storage, UNLIMITED_SPACE)) {
            staged.install().commit();
        }

        LocalObjectStorage restored = localStorage(target);
        assertThat(restored.exists(evidence)).isTrue();
        assertThat(restored.open(evidence)).hasValueSatisfying(file -> {
            assertThat(file.metadata().key()).isEqualTo(evidence);
            assertThat(file.metadata().contentType()).isEqualTo("image/png");
        });
        try (InputStream served = restored.read(evidence)) {
            assertThat(new String(served.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("image");
        }
        try (Stream<ListedObject> listed = restored.list("instance/")) {
            assertThat(listed.map(ListedObject::key)).containsExactlyInAnyOrder(evidence, replay);
        }
        assertThat(restored.readOwnershipMarker("instance")).contains("owner");
        assertThat(restored.deleteAll(List.of(replay))).isEmpty();
        assertThat(restored.exists(replay)).isFalse();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).noneMatch(name -> name.contains(".tmp/"));
        }
    }

    @Test
    void everyObjectIsArchivedRightAfterItsMetadata() throws IOException {
        Path source = directory.resolve("source");
        LocalObjectStorage original = localStorage(source);
        store(original, "instance/a.png", "a", "image/png");
        store(original, "instance/b.png", "b", "image/png");
        Path archive = directory.resolve("backup.zip");

        writeArchive(archive, new StorageFolder(source));

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName).filter(name -> name.startsWith("storage.files/")).toList())
                .containsExactly("storage.files/.meta/instance/a.png", "storage.files/instance/a.png",
                    "storage.files/.meta/instance/b.png", "storage.files/instance/b.png");
        }
    }

    @Test
    void discardingAStagedRestoreLeavesTheTargetUntouched() throws IOException {
        Path archive = directory.resolve("crafted.zip");
        writeEntries(archive, Map.of("storage.properties", "files=1\nbytes=4\n", "storage.files/modl_instance/new.txt", "data"));
        Path target = directory.resolve("target");
        write(target.resolve("modl_instance/kept.txt"), "kept");

        try (StorageArchive storage = StorageArchive.open(archive);
             StagedStorage staged = new StorageFolder(target).stage(storage, UNLIMITED_SPACE)) {
            assertThat(staged.summary()).isEqualTo(new ArchivedStorage(1, 4));
        }

        assertThat(contents(target)).isEqualTo(Map.of("modl_instance/kept.txt", "kept"));
    }

    @Test
    void revertingAnInstalledRestorePutsThePreviousFilesBack() throws IOException {
        Path archive = directory.resolve("crafted.zip");
        writeEntries(archive, Map.of("storage.properties", "files=1\nbytes=4\n", "storage.files/modl_instance/new.txt", "data"));
        Path target = directory.resolve("target");
        write(target.resolve("modl_instance/kept.txt"), "kept");

        try (StorageArchive storage = StorageArchive.open(archive);
             StagedStorage staged = new StorageFolder(target).stage(storage, UNLIMITED_SPACE)) {
            InstalledStorage installed = staged.install();
            assertThat(contents(target)).containsOnlyKeys("modl_instance/new.txt", ".modl-restore-replaced-" + replacedSuffix(target)
                + "/modl_instance/kept.txt");
            installed.revert();
        }

        assertThat(contents(target)).isEqualTo(Map.of("modl_instance/kept.txt", "kept"));
    }

    @Test
    void aStorageFolderReachedThroughASymbolicLinkIsBackedUpWithItsFiles() throws IOException {
        Path real = directory.resolve("real-storage");
        write(real.resolve("modl_instance/a.png"), "image");
        Path linked = Files.createSymbolicLink(directory.resolve("storage"), real);
        Path archive = directory.resolve("backup.zip");

        assertThat(writeArchive(archive, new StorageFolder(linked))).isEqualTo(new ArchivedStorage(1, 5));
        assertThat(new StorageFolder(linked).contains(linked.resolve("inside.zip"))).isTrue();
        assertThat(new StorageFolder(real).contains(linked.resolve("inside.zip"))).isTrue();
        assertThat(new StorageFolder(linked).contains(directory.resolve("outside.zip"))).isFalse();
    }

    @Test
    void anUnfinishedEarlierRestoreMustBeResolvedFirst() throws IOException {
        Path archive = directory.resolve("crafted.zip");
        writeEntries(archive, Map.of("storage.properties", "files=1\nbytes=4\n", "storage.files/modl_instance/new.txt", "data"));
        Path target = directory.resolve("target");
        write(target.resolve(".modl-restore-replaced-1/modl_instance/old.txt"), "the only copy");

        try (StorageArchive storage = StorageArchive.open(archive)) {
            assertThatThrownBy(() -> new StorageFolder(target).stage(storage, UNLIMITED_SPACE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not finish");
        }

        assertThat(contents(target)).isEqualTo(Map.of(".modl-restore-replaced-1/modl_instance/old.txt", "the only copy"));
    }

    @Test
    void symbolicLinksInTheStorageFolderFailTheBackup() throws IOException {
        Path source = directory.resolve("source");
        write(source.resolve("modl_instance/a.png"), "image");
        Files.createSymbolicLink(source.resolve("linked"), directory);

        assertThatThrownBy(() -> new StorageFolder(source).sizeBytes())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("symbolic links");
    }

    @Test
    void entriesThatEscapeTheStorageFolderAreRejectedBeforeAnythingIsWritten() throws IOException {
        Path archive = directory.resolve("escape.zip");
        writeEntries(archive, Map.of("storage.properties", "files=1\nbytes=4\n", "storage.files/../escaped.txt", "evil"));

        assertThatThrownBy(() -> StorageArchive.open(archive))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("not a valid file name");
        assertThat(directory.resolve("escaped.txt")).doesNotExist();
    }

    @Test
    void archivesWhoseFilesDoNotMatchTheSummaryAreRejected() throws IOException {
        Path missingFiles = directory.resolve("short.zip");
        writeEntries(missingFiles, Map.of("storage.properties", "files=2\nbytes=8\n", "storage.files/modl_instance/only.txt", "data"));
        Path missingSummary = directory.resolve("unlisted.zip");
        writeEntries(missingSummary, Map.of("storage.files/modl_instance/only.txt", "data"));
        Path invalidSummary = directory.resolve("invalid.zip");
        writeEntries(invalidSummary, Map.of("storage.properties", "files=many\n"));

        assertThatThrownBy(() -> StorageArchive.open(missingFiles)).isInstanceOf(IOException.class).hasMessageContaining("damaged");
        assertThatThrownBy(() -> StorageArchive.open(missingSummary)).isInstanceOf(IOException.class).hasMessageContaining("damaged");
        assertThatThrownBy(() -> StorageArchive.open(invalidSummary)).isInstanceOf(IOException.class).hasMessageContaining("damaged");
    }

    @Test
    void stagingIsRefusedWhenTheDiskCannotHoldTheFiles() throws IOException {
        Path archive = directory.resolve("large.zip");
        writeEntries(archive, Map.of("storage.properties", "files=1\nbytes=4\n", "storage.files/modl_instance/new.txt", "data"));
        BackupDiskSpace full = new BackupDiskSpace(new DiskSpaceFloor(0, 0), path -> new FakeFileStore(100, 0));

        try (StorageArchive storage = StorageArchive.open(archive)) {
            assertThatThrownBy(() -> new StorageFolder(directory.resolve("target")).stage(storage, full))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not enough free disk space");
        }
    }

    @Test
    void archivesWithoutUploadedFilesHaveNothingToStage() throws IOException {
        Path archive = directory.resolve("database-only.zip");
        writeEntries(archive, Map.of("manifest.json", "{}"));

        try (StorageArchive storage = StorageArchive.open(archive)) {
            assertThat(storage.summary()).isEmpty();
            assertThatThrownBy(() -> new StorageFolder(directory.resolve("target")).stage(storage, UNLIMITED_SPACE))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    private static LocalObjectStorage localStorage(Path root) {
        return new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"), Clock.systemUTC());
    }

    private static void store(LocalObjectStorage storage, String key, String content, String contentType) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storage.write(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
    }

    private static String replacedSuffix(Path target) throws IOException {
        try (Stream<Path> children = Files.list(target)) {
            return children.map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith(".modl-restore-replaced-"))
                .map(name -> name.substring(".modl-restore-replaced-".length()))
                .findFirst()
                .orElseThrow();
        }
    }

    private static ArchivedStorage writeArchive(Path archive, StorageFolder storage) throws IOException {
        AtomicReference<ArchivedStorage> written = new AtomicReference<>();
        BackupFiles.writeAtomically(archive, output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                written.set(StorageArchive.write(storage, zip));
            }
        });
        return written.get();
    }

    private static void writeEntries(Path archive, Map<String, String> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Map<String, String> contents(Path root) throws IOException {
        Map<String, String> contents = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            contents.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file));
        }
        return contents;
    }
}
