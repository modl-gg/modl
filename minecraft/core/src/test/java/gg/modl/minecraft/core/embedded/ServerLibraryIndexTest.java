package gg.modl.minecraft.core.embedded;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLibraryIndexTest {
    private static final String SHA = "a".repeat(64);
    private static final String FIXTURE = "server-library-index.txt";

    @Test
    void readsTheIndexTheBuildWrites() throws IOException {
        ServerLibraryIndex index = ServerLibraryIndex.parse(fixture());

        assertEquals(Arrays.asList("modl-server-3.0.0-plain.jar", "proto-3.0.0.jar"), fileNames(index.packed()));
        assertEquals(Arrays.asList("netty-transport-native-epoll-4.2.20.Final-linux-x86_64.jar", "spring-core-7.1.0.jar"),
                fileNames(index.central().stream().map(CentralLibrary::getFile).collect(Collectors.toList())));
        CentralLibrary nativeTransport = index.central().get(0);
        assertEquals("io.netty:netty-transport-native-epoll:4.2.20.Final:linux-x86_64", nativeTransport.getArtifact().toString());
        assertEquals("linux-x86_64", nativeTransport.getArtifact().getClassifier());
        assertEquals(52_424, nativeTransport.getFile().getSize());
        assertEquals("3".repeat(64), nativeTransport.getFile().getSha256());
        assertEquals(4, index.all().size());
    }

    @Test
    void rejectsAnIndexOfAnotherFormat() {
        IOException failure = assertThrows(IOException.class,
                () -> ServerLibraryIndex.parse(SHA + " spring-core-7.1.0.jar\n"));

        assertTrue(failure.getMessage().contains(ServerLibraryIndex.HEADER), failure.getMessage());
    }

    @Test
    void rejectsMalformedLines() {
        String[] malformed = {
                "packed " + SHA + " 10",
                "packed " + SHA.toUpperCase(Locale.ROOT) + " 10 server.jar",
                "packed " + SHA + " -1 server.jar",
                "packed " + SHA + " ten server.jar",
                "packed " + SHA + " 10 ../server.jar",
                "packed " + SHA + " 10 lib/server.jar",
                "central " + SHA + " 10 org.example:library",
                "central " + SHA + " 10 org.example:..:1.0",
                "mirror " + SHA + " 10 org.example:library:1.0",
        };
        for (String line : malformed) {
            assertThrows(IOException.class, () -> ServerLibraryIndex.parse(ServerLibraryIndex.HEADER + "\n" + line + "\n"), line);
        }
    }

    @Test
    void rejectsTwoEntriesForOneFile() {
        String index = ServerLibraryIndex.HEADER + "\n"
                + "packed " + SHA + " 10 spring-core-7.1.0.jar\n"
                + "central " + SHA + " 10 org.springframework:spring-core:7.1.0\n";

        assertThrows(IOException.class, () -> ServerLibraryIndex.parse(index));
    }

    @Test
    void rejectsAnIndexWithoutLibraries() {
        assertThrows(IOException.class, () -> ServerLibraryIndex.parse(ServerLibraryIndex.HEADER + "\n"));
    }

    private static List<String> fileNames(List<ServerLibrary> libraries) {
        return libraries.stream().map(ServerLibrary::getFileName).collect(Collectors.toList());
    }

    private static String fixture() throws IOException {
        try (InputStream input = ServerLibraryIndexTest.class.getResourceAsStream(FIXTURE)) {
            if (input == null) throw new IOException("missing " + FIXTURE);
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) content.write(buffer, 0, read);
            return new String(content.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
