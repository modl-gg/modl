package gg.modl.minecraft.core.embedded;

import gg.modl.update.ArtifactKind;
import gg.modl.update.ReleaseArtifact;
import gg.modl.update.ReleaseVersion;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class PayloadPin {
    static final String RESOURCE = "modl-server/payload.txt";
    static final String HEADER = "modl-embedded-payload 1";
    private static final int MAX_BYTES = 1024;
    private static final String VERSION = "version";
    private static final String SHA256 = "sha256";
    private static final String SIZE = "size";

    private final ReleaseVersion version;
    private final ReleaseArtifact artifact;

    private PayloadPin(ReleaseVersion version, ReleaseArtifact artifact) {
        this.version = version;
        this.artifact = artifact;
    }

    static PayloadPin bundledIn(ClassLoader pluginLoader) throws IOException {
        try (InputStream input = pluginLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("The plugin jar has no " + RESOURCE + ". Download the full modl plugin jar.");
            return parse(readBounded(input));
        }
    }

    static PayloadPin parse(String text) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) lines.add(line.trim());
        }
        if (lines.size() != 4 || !lines.get(0).equals(HEADER)) throw malformed("it is not a " + HEADER + " pin");
        ReleaseVersion version = ReleaseVersion.tryParse(field(lines.get(1), VERSION));
        if (version == null) throw malformed("it names no release version");
        try {
            long size = Long.parseLong(field(lines.get(3), SIZE));
            return new PayloadPin(version, ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, version, field(lines.get(2), SHA256), size));
        } catch (IllegalArgumentException invalid) {
            throw malformed(invalid.getMessage());
        }
    }

    ReleaseVersion version() {
        return version;
    }

    ReleaseArtifact artifact() {
        return artifact;
    }

    private static String field(String line, String key) throws IOException {
        String prefix = key + " ";
        if (!line.startsWith(prefix)) throw malformed("expected " + key + " in: " + line);
        return line.substring(prefix.length());
    }

    private static String readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        byte[] buffer = new byte[MAX_BYTES];
        int read;
        while ((read = input.read(buffer)) != -1) {
            text.write(buffer, 0, read);
            if (text.size() > MAX_BYTES) throw malformed("it is larger than " + MAX_BYTES + " bytes");
        }
        return new String(text.toByteArray(), StandardCharsets.UTF_8);
    }

    private static IOException malformed(@Nullable String detail) {
        return new IOException("The plugin jar's " + RESOURCE + " is invalid (" + detail + "). Download the full modl plugin jar.");
    }
}
