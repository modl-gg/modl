package gg.modl.minecraft.core.embedded;

import lombok.Value;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Value
public class InstalledPayload {
    String version;
    List<Path> jars;
    int extractedCount;
    int downloadedCount;

    public boolean changedAnything() {
        return extractedCount > 0 || downloadedCount > 0;
    }

    public Set<String> libraryFileNames() {
        Set<String> names = new HashSet<>();
        for (Path jar : jars) names.add(jar.getFileName().toString());
        return Collections.unmodifiableSet(names);
    }
}
