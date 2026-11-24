package gg.modl.backend.storage.local;

import gg.modl.backend.infrastructure.disk.DurableFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

final class ObjectMetadataFile {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ObjectMetadataFile() {
    }

    static void writeDurably(Path destination, Contents contents) throws IOException {
        DurableFiles.writeText(destination, JSON.writeValueAsString(contents));
    }

    static Optional<Contents> read(Path metadataPath) {
        try {
            return Optional.ofNullable(JSON.readValue(Files.readString(metadataPath, StandardCharsets.UTF_8), Contents.class))
                .filter(contents -> contents.key() != null);
        } catch (IOException | JacksonException e) {
            return Optional.empty();
        }
    }

    record Contents(String key, @Nullable String contentType) {
    }
}
