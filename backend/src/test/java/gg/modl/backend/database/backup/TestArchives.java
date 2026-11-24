package gg.modl.backend.database.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.UnaryOperator;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class TestArchives {
    private TestArchives() {
    }

    static byte[] entry(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile()); InputStream input = zip.getInputStream(zip.getEntry(name))) {
            return input.readAllBytes();
        }
    }

    static String manifest(Path archive) throws IOException {
        return new String(entry(archive, ArchiveManifest.ENTRY_NAME), StandardCharsets.UTF_8);
    }

    static Path withEntry(Path source, Path target, String name, UnaryOperator<byte[]> change) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                if (entry.getName().equals(name)) {
                    bytes = change.apply(bytes);
                    if (bytes.length == 0) {
                        continue;
                    }
                }
                putStored(output, entry.getName(), bytes);
            }
        }
        return target;
    }

    static Path withFlippedByte(Path source, Path target, String marker) throws IOException {
        Path stored = withEntry(source, target, "", UnaryOperator.identity());
        byte[] content = Files.readAllBytes(stored);
        byte[] needle = marker.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index <= content.length - needle.length; index++) {
            if (Arrays.equals(content, index, index + needle.length, needle, 0, needle.length)) {
                content[index + needle.length - 1] = (byte) 'X';
                return Files.write(stored, content);
            }
        }
        throw new IllegalStateException("The archive does not contain " + marker);
    }

    private static void putStored(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }
}
