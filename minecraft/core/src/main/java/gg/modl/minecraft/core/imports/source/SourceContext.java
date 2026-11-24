package gg.modl.minecraft.core.imports.source;

import gg.modl.minecraft.core.imports.sql.JdbcConnections;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.Builder;
import lombok.Value;

import java.io.File;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Value
@Builder
public class SourceContext {
    Path pluginsFolder;
    Path serverRoot;
    Path scratch;
    JdbcConnections connections;
    Predicate<String> pluginEnabled;
    BooleanSupplier offlineUuids;
    Supplier<String> defaultReason;
    LongSupplier clock;
    PluginLogger logger;

    public Path pluginFolder(String name) {
        File[] children = pluginsFolder.toFile().listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                if (child.getName().equalsIgnoreCase(name)) return child.toPath();
            }
        }
        return pluginsFolder.resolve(name);
    }

    public long now() {
        return clock.getAsLong();
    }
}
