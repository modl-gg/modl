package gg.modl.backend.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedEntryStreamTest {
    private static final String ENTRY = "manifest.json";

    @TempDir
    Path directory;

    @Test
    void anEntryLargerThanItsCallerAllowsIsRefusedBeforeItIsRead() throws IOException {
        Path archive = archiveWith("x".repeat(2048));

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(ENTRY);
            assertThatThrownBy(() -> VerifiedEntryStream.openAtMost(zip, entry, 1024)).isInstanceOf(IOException.class)
                .hasMessageContaining(ENTRY);
            try (VerifiedEntryStream input = VerifiedEntryStream.openAtMost(zip, entry, 4096)) {
                assertThat(input.readAllBytes()).hasSize(2048);
                input.requireIntact();
            }
        }
    }

    @Test
    void anEntryMustHoldExactlyTheBytesItDeclares() throws IOException {
        Path archive = archiveWith("{\"tables\":[]}");
        Path tampered = directory.resolve("tampered.zip");
        byte[] bytes = Files.readAllBytes(archive);
        Files.write(tampered, withDeclaredSizeShrunk(bytes, "{\"tables\":[]}".length()));

        try (ZipFile zip = new ZipFile(tampered.toFile());
             VerifiedEntryStream input = VerifiedEntryStream.open(zip, zip.getEntry(ENTRY))) {
            assertThatThrownBy(input::readAllBytes).isInstanceOf(IOException.class).hasMessageContaining("declared");
        }
    }

    private Path archiveWith(String content) throws IOException {
        Path archive = directory.resolve("archive.zip");
        try (OutputStream file = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(file)) {
            ZipEntry entry = new ZipEntry(ENTRY);
            entry.setMethod(ZipEntry.DEFLATED);
            zip.putNextEntry(entry);
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }

    private static byte[] withDeclaredSizeShrunk(byte[] archive, int size) {
        byte[] tampered = archive.clone();
        int centralDirectory = lastIndexOf(tampered, new byte[] {0x50, 0x4b, 0x01, 0x02});
        int uncompressedSizeOffset = centralDirectory + 24;
        int shrunk = size - 4;
        tampered[uncompressedSizeOffset] = (byte) shrunk;
        tampered[uncompressedSizeOffset + 1] = (byte) (shrunk >> 8);
        tampered[uncompressedSizeOffset + 2] = (byte) (shrunk >> 16);
        tampered[uncompressedSizeOffset + 3] = (byte) (shrunk >> 24);
        return tampered;
    }

    private static int lastIndexOf(byte[] data, byte[] signature) {
        for (int index = data.length - signature.length; index >= 0; index--) {
            boolean match = true;
            for (int offset = 0; offset < signature.length && match; offset++) {
                match = data[index + offset] == signature[offset];
            }
            if (match) {
                return index;
            }
        }
        throw new IllegalArgumentException("No central directory header");
    }
}
