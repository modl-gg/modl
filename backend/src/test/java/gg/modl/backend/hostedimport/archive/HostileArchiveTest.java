package gg.modl.backend.hostedimport.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.ImportTestContexts;
import gg.modl.backend.hostedimport.mapping.ArchiveAnalyzer;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostileArchiveTest {
    private static final int END_RECORD_SIZE = 22;
    private static final int ZIP64_END_RECORD_SIZE = 56;
    private static final int ZIP64_LOCATOR_SIZE = 20;
    private static final int CENTRAL_RECORD_SIZE = 46;
    private static final int CENTRAL_METHOD = 10;
    private static final int CENTRAL_COMPRESSED_SIZE = 20;
    private static final int CENTRAL_SIZE = 24;
    private static final int CENTRAL_LOCAL_OFFSET = 42;
    private static final int MORE_THAN_ZIP32_HOLDS = 65_600;
    private static final int EXPANDED_BOMB = 32 * 1024 * 1024;
    private static final int READ_BUFFER = 65536;
    private static final String BOMB = "storage/bomb.bin";

    @TempDir
    Path directory;

    @Test
    void anArchiveWithMoreEntriesThanZip32HoldsIsRead() throws IOException {
        Path archive = directory.resolve("many.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (int index = 1; index < MORE_THAN_ZIP32_HOLDS; index++) {
                zip.putNextEntry(new ZipEntry("storage/" + index));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(HostedArchive.MANIFEST));
            zip.closeEntry();
        }

        assertThat(CentralDirectoryCheck.read(archive).entries()).isEqualTo(MORE_THAN_ZIP32_HOLDS);
        try (HostedArchive opened = HostedArchive.open(archive)) {
            assertThat(opened.entryCount()).isEqualTo(MORE_THAN_ZIP32_HOLDS);
        }
    }

    @Test
    void aZip64EndRecordOfASmallArchiveIsVerifiedLikeAnyOther() throws IOException {
        Path archive = new HostedArchiveFixture().object("evidence/a.png", "png").write(directory);
        Files.write(archive, zip64(Files.readAllBytes(archive), UnaryOperator.identity()));

        try (HostedArchive opened = HostedArchive.open(archive); VerifiedEntryStream stream = opened.openManifest()) {
            ArchiveManifest manifest = ManifestReader.read(stream);
            assertThat(new ArchiveAnalyzer(opened, manifest, ImportTestContexts.NOW, 365, () -> false)
                .analyse(true, done -> { }).keyPlan().size()).isEqualTo(1);
        }
    }

    @Test
    void forgedZip64DirectoriesAreRefusedBeforeTheyAreLoaded() throws IOException {
        byte[] plain = zipOf(new byte[] {1});

        assertRefused(zip64(plain, end -> end.withEntries(ArchiveLimits.MAX_ENTRIES + 1)), ImportReason.ARCHIVE_TOO_MANY_FILES);
        assertRefused(zip64(plain, end -> end.withEntries(-1)), ImportReason.ARCHIVE_DAMAGED);
        assertRefused(zip64(plain, end -> end.withSize(ArchiveLimits.MAX_CENTRAL_DIRECTORY_BYTES + 1)),
            ImportReason.ARCHIVE_TOO_MANY_FILES);
        assertRefused(zip64(plain, end -> end.withSize(-1)), ImportReason.ARCHIVE_DAMAGED);
        assertRefused(zip64(plain, end -> end.withOffset(plain.length * 2L)), ImportReason.ARCHIVE_DAMAGED);
        assertRefused(zip64(plain, end -> end.withOffset(Long.MAX_VALUE)), ImportReason.ARCHIVE_DAMAGED);

        byte[] withoutLocator = zip64(plain, UnaryOperator.identity());
        littleEndian(withoutLocator).putInt(withoutLocator.length - END_RECORD_SIZE - ZIP64_LOCATOR_SIZE, 0);
        assertRefused(withoutLocator, ImportReason.ARCHIVE_DAMAGED);
    }

    @Test
    void anEntryThatExpandsBeyondItsDeclaredSizeIsStoppedWhileReading() throws IOException {
        int declared = 1024;
        byte[] bomb = zipOf(new byte[EXPANDED_BOMB]);
        littleEndian(bomb).putInt(centralRecord(bomb, BOMB) + CENTRAL_SIZE, declared);
        Path archive = Files.write(directory.resolve("bomb.zip"), bomb);

        try (HostedArchive opened = HostedArchive.open(archive);
             VerifiedEntryStream stream = opened.open(BOMB, declared, null)) {
            assertFailure(stream::drainAndVerify, ImportReason.ARCHIVE_CHECKSUM);
            assertThat(stream.bytesRead()).isLessThanOrEqualTo(declared + READ_BUFFER);
        }
    }

    @Test
    void anEntryLargerThanTheManifestDeclaresIsNeverInflated() throws IOException {
        Path archive = Files.write(directory.resolve("bomb.zip"), zipOf(new byte[EXPANDED_BOMB]));

        try (HostedArchive opened = HostedArchive.open(archive)) {
            assertFailure(() -> opened.open(BOMB, 1024, null), ImportReason.ARCHIVE_ENTRIES);
        }
    }

    @Test
    void aManifestDeclaredBeyondItsLimitIsNeverInflated() throws IOException {
        byte[] bytes = zipOf(new byte[] {1});
        littleEndian(bytes).putInt(centralRecord(bytes, HostedArchive.MANIFEST) + CENTRAL_SIZE,
            (int) (ArchiveLimits.MAX_MANIFEST_BYTES + 1));
        Path archive = Files.write(directory.resolve("manifest.zip"), bytes);

        try (HostedArchive opened = HostedArchive.open(archive)) {
            assertFailure(opened::openManifest, ImportReason.ARCHIVE_TOO_LARGE);
        }
    }

    @Test
    void entriesSharingTheSameCompressedBytesAreRefused() throws IOException {
        byte[] incompressible = new byte[256 * 1024];
        new Random(7).nextBytes(incompressible);
        byte[] bytes = zipOf(incompressible);
        int shared = centralRecord(bytes, BOMB);
        int overlapping = centralRecord(bytes, HostedArchive.MANIFEST);
        ByteBuffer buffer = littleEndian(bytes);
        for (int field : List.of(CENTRAL_COMPRESSED_SIZE, CENTRAL_SIZE, CENTRAL_LOCAL_OFFSET)) {
            buffer.putInt(overlapping + field, buffer.getInt(shared + field));
        }

        assertRefused(bytes, ImportReason.ARCHIVE_DAMAGED);
    }

    @Test
    void duplicateNamesAndOtherCompressionMethodsAreRefused() throws IOException {
        byte[] duplicated = zipOfEntries(List.of(HostedArchive.MANIFEST, "manifest.jsom"), new byte[] {1});
        int renamed = centralRecord(duplicated, "manifest.jsom") + CENTRAL_RECORD_SIZE;
        System.arraycopy(HostedArchive.MANIFEST.getBytes(StandardCharsets.UTF_8), 0, duplicated, renamed, HostedArchive.MANIFEST.length());
        assertRefused(duplicated, ImportReason.ARCHIVE_ENTRIES);

        byte[] stored = zipOf(new byte[] {1});
        littleEndian(stored).putShort(centralRecord(stored, BOMB) + CENTRAL_METHOD, (short) ZipEntry.STORED);
        Path archive = Files.write(directory.resolve("stored.zip"), stored);
        assertThatThrownBy(() -> HostedArchive.open(archive)).isInstanceOf(ImportFailure.class)
            .extracting(failure -> ((ImportFailure) failure).reason())
            .isIn(ImportReason.ARCHIVE_ENTRIES, ImportReason.ARCHIVE_NOT_ZIP);
    }

    private void assertRefused(byte[] bytes, ImportReason reason) throws IOException {
        Path archive = Files.write(directory.resolve("refused.zip"), bytes);
        assertFailure(() -> HostedArchive.open(archive), reason);
    }

    private static void assertFailure(ThrowingCallable action, ImportReason reason) {
        assertThatThrownBy(action).isInstanceOf(ImportFailure.class)
            .extracting(failure -> ((ImportFailure) failure).reason()).isEqualTo(reason);
    }

    private byte[] zipOf(byte[] content) throws IOException {
        return zipOfEntries(List.of(BOMB, HostedArchive.MANIFEST), content);
    }

    private byte[] zipOfEntries(List<String> names, byte[] firstContent) throws IOException {
        Path archive = directory.resolve("source.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int index = 0; index < names.size(); index++) {
                zip.putNextEntry(new ZipEntry(names.get(index)));
                zip.write(index == 0 ? firstContent : new byte[] {1});
                zip.closeEntry();
            }
        }
        return Files.readAllBytes(archive);
    }

    private static int centralRecord(byte[] zip, String name) {
        ByteBuffer buffer = littleEndian(zip);
        int position = buffer.getInt(zip.length - END_RECORD_SIZE + 16);
        List<String> seen = new ArrayList<>();
        while (position + CENTRAL_RECORD_SIZE <= zip.length - END_RECORD_SIZE) {
            int nameLength = Short.toUnsignedInt(buffer.getShort(position + 28));
            String found = new String(zip, position + CENTRAL_RECORD_SIZE, nameLength, StandardCharsets.UTF_8);
            if (found.equals(name)) {
                return position;
            }
            seen.add(found);
            position += CENTRAL_RECORD_SIZE + nameLength + Short.toUnsignedInt(buffer.getShort(position + 30))
                + Short.toUnsignedInt(buffer.getShort(position + 32));
        }
        throw new AssertionError(name + " is not among " + seen);
    }

    private static byte[] zip64(byte[] zip, UnaryOperator<DirectoryEnd> forgery) {
        ByteBuffer source = littleEndian(zip);
        int end = zip.length - END_RECORD_SIZE;
        DirectoryEnd written = forgery.apply(new DirectoryEnd(Short.toUnsignedLong(source.getShort(end + 10)),
            Integer.toUnsignedLong(source.getInt(end + 12)), Integer.toUnsignedLong(source.getInt(end + 16))));
        ByteBuffer out = ByteBuffer.allocate(zip.length + ZIP64_END_RECORD_SIZE + ZIP64_LOCATOR_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        out.put(zip, 0, end);
        out.putInt(0x06064b50).putLong(ZIP64_END_RECORD_SIZE - 12).putShort((short) 45).putShort((short) 45).putInt(0).putInt(0)
            .putLong(written.entries()).putLong(written.entries()).putLong(written.size()).putLong(written.offset());
        out.putInt(0x07064b50).putInt(0).putLong(end).putInt(1);
        out.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) -1).putShort((short) -1)
            .putInt(-1).putInt(-1).putShort((short) 0);
        return out.array();
    }

    private static ByteBuffer littleEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private record DirectoryEnd(long entries, long size, long offset) {
        private DirectoryEnd withEntries(long forged) {
            return new DirectoryEnd(forged, size, offset);
        }

        private DirectoryEnd withSize(long forged) {
            return new DirectoryEnd(entries, forged, offset);
        }

        private DirectoryEnd withOffset(long forged) {
            return new DirectoryEnd(entries, size, forged);
        }
    }
}
