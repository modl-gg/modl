package gg.modl.backend.storage.backend;

import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.UploadSigner;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

final class StorageTestBackends {
    private StorageTestBackends() {
    }

    static LocalObjectStorage local(Path root) {
        return new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"), Clock.systemUTC());
    }

    static void put(ObjectStorage storage, String key, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storage.write(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
    }

    static String read(ObjectStorage storage, String key) {
        try (InputStream input = storage.read(key)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
