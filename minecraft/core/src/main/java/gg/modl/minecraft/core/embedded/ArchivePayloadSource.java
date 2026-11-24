package gg.modl.minecraft.core.embedded;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ArchivePayloadSource implements PayloadSource {
    private final String fileName;
    private final String base;
    private final Set<String> entries;

    private ArchivePayloadSource(String fileName, String base, Set<String> entries) {
        this.fileName = fileName;
        this.base = base;
        this.entries = entries;
    }

    static ArchivePayloadSource open(Path zip) throws IOException {
        Set<String> entries = new HashSet<>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> all = archive.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (!entry.isDirectory()) entries.add(entry.getName());
            }
        }
        return new ArchivePayloadSource(zip.getFileName().toString(), "jar:" + zip.toUri() + "!/", entries);
    }

    @Override
    public @Nullable URL locate(String name) {
        if (!entries.contains(name)) return null;
        try {
            return new URL(base + name);
        } catch (MalformedURLException invalid) {
            return null;
        }
    }

    @Override
    public String displayName() {
        return fileName;
    }
}
