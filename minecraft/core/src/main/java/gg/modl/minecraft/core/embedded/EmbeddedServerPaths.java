package gg.modl.minecraft.core.embedded;

import lombok.Value;

import java.nio.file.Path;

@Value
public class EmbeddedServerPaths {
    Path dataDirectory;
    Path serverDirectory;
    Path libraryDirectory;
    Path logDirectory;
    Path lockFile;
    Path setupCodeFile;

    public static EmbeddedServerPaths forPluginDataFolder(Path pluginDataFolder) {
        Path absolute = pluginDataFolder.toAbsolutePath().normalize();
        Path server = absolute.resolve("server");
        return new EmbeddedServerPaths(absolute.resolve("data"), server, server.resolve(EmbeddedPayload.LIBRARY_DIRECTORY),
                server.resolve("logs"), server.resolve("server.lock"), absolute.resolve("setup-code.txt"));
    }

    public Path serverLog() {
        return logDirectory.resolve("server.log");
    }
}
