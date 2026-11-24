package gg.modl.minecraft.core.embedded;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

@FunctionalInterface
public interface PayloadSource {
    @Nullable URL locate(String name);

    default String displayName() {
        return "The embedded server payload";
    }

    static PayloadSource archive(Path zip) throws IOException {
        return ArchivePayloadSource.open(zip);
    }
}
