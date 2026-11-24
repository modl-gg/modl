package gg.modl.minecraft.core.connection;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;

public final class RconPorts {
    public static final String SERVER_PROPERTIES = "server.properties";
    static final String ENABLED = "enable-rcon";
    static final String PORT = "rcon.port";
    private static final int DEFAULT_PORT = 25575;

    private RconPorts() {
    }

    public static Set<Integer> fromServerProperties(Path serverProperties) {
        if (!Files.isRegularFile(serverProperties)) return Collections.emptySet();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(serverProperties, StandardCharsets.ISO_8859_1)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            return Collections.emptySet();
        }
        if (!Boolean.parseBoolean(properties.getProperty(ENABLED, "false").trim())) return Collections.emptySet();
        try {
            return Collections.singleton(Integer.parseInt(properties.getProperty(PORT, String.valueOf(DEFAULT_PORT)).trim()));
        } catch (NumberFormatException e) {
            return Collections.emptySet();
        }
    }
}
