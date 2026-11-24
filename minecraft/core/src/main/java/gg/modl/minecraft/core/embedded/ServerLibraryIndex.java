package gg.modl.minecraft.core.embedded;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class ServerLibraryIndex {
    static final String FILE_NAME = "index.txt";
    static final String HEADER = "modl-server-libraries 2";
    private static final String PACKED = "packed";
    private static final String CENTRAL = "central";
    private static final String FIELD_SEPARATOR = " ";
    private static final int FIELDS = 4;
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PACKED_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*\\.jar");

    private final List<ServerLibrary> packed;
    private final List<CentralLibrary> central;

    private ServerLibraryIndex(List<ServerLibrary> packed, List<CentralLibrary> central) {
        this.packed = Collections.unmodifiableList(packed);
        this.central = Collections.unmodifiableList(central);
    }

    static ServerLibraryIndex parse(String text) throws IOException {
        List<String> lines = nonBlankLines(text);
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            throw new IOException(FILE_NAME + " is not a " + HEADER + " index.");
        }
        List<ServerLibrary> packed = new ArrayList<>();
        List<CentralLibrary> central = new ArrayList<>();
        Set<String> fileNames = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(FIELD_SEPARATOR, -1);
            if (fields.length != FIELDS || !SHA256_HEX.matcher(fields[1]).matches()) throw malformed(line);
            long size = size(fields[2], line);
            ServerLibrary file;
            if (PACKED.equals(fields[0]) && PACKED_FILE_NAME.matcher(fields[3]).matches()) {
                file = new ServerLibrary(fields[3], fields[1], size);
                packed.add(file);
            } else if (CENTRAL.equals(fields[0])) {
                MavenArtifact artifact = MavenArtifact.parse(fields[3]);
                file = new ServerLibrary(artifact.fileName(), fields[1], size);
                central.add(new CentralLibrary(artifact, file));
            } else {
                throw malformed(line);
            }
            if (!fileNames.add(file.getFileName())) throw malformed(line);
        }
        if (fileNames.isEmpty()) throw new IOException(FILE_NAME + " lists no libraries");
        return new ServerLibraryIndex(packed, central);
    }

    List<ServerLibrary> packed() {
        return packed;
    }

    List<CentralLibrary> central() {
        return central;
    }

    List<ServerLibrary> all() {
        List<ServerLibrary> all = new ArrayList<>(packed);
        for (CentralLibrary library : central) all.add(library.getFile());
        return Collections.unmodifiableList(all);
    }

    private static List<String> nonBlankLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        return lines;
    }

    private static long size(String field, String line) throws IOException {
        try {
            long size = Long.parseLong(field);
            if (size < 0) throw malformed(line);
            return size;
        } catch (NumberFormatException notANumber) {
            throw malformed(line);
        }
    }

    private static IOException malformed(String line) {
        return new IOException("Malformed line in " + FILE_NAME + ": " + line);
    }
}
