package gg.modl.minecraft.core.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RconPortsTest {
    @TempDir
    Path folder;

    @Test
    void anEnabledRconListenerReportsItsPort() throws IOException {
        assertEquals(Collections.singleton(25580), read("enable-rcon=true", "rcon.port=25580"));
        assertEquals(Collections.singleton(25575), read("enable-rcon=true"));
    }

    @Test
    void aDisabledOrMissingRconListenerReportsNothing() throws IOException {
        assertEquals(Collections.emptySet(), read("enable-rcon=false", "rcon.port=25580"));
        assertEquals(Collections.emptySet(), read("enable-rcon=true", "rcon.port=not-a-port"));
        assertEquals(Collections.emptySet(), RconPorts.fromServerProperties(folder.resolve("missing.properties")));
    }

    private Set<Integer> read(String... lines) throws IOException {
        Path file = folder.resolve(RconPorts.SERVER_PROPERTIES);
        Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.ISO_8859_1));
        return RconPorts.fromServerProperties(file);
    }
}
