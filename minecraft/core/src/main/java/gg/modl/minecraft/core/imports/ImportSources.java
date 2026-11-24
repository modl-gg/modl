package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.source.ImportSource;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ImportSources {
    private final List<ImportSource> sources;
    private volatile List<String> detected = Collections.emptyList();

    public ImportSources(List<ImportSource> sources) {
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
    }

    @Nullable ImportSource byId(@Nullable String id) {
        if (id == null) return null;
        String wanted = id.toLowerCase(Locale.ROOT);
        for (ImportSource source : sources) {
            if (source.id().equals(wanted)) return source;
        }
        return null;
    }

    public List<String> detectedIds() {
        return detected;
    }

    List<String> detect() {
        List<String> found = new ArrayList<>();
        for (ImportSource source : sources) {
            if (isPresent(source)) found.add(source.id());
        }
        detected = Collections.unmodifiableList(found);
        return detected;
    }

    String supportedIds() {
        List<String> ids = new ArrayList<>();
        for (ImportSource source : sources) ids.add(source.id());
        return String.join(", ", ids);
    }

    String displayNameOf(@Nullable String id) {
        ImportSource source = byId(id);
        if (source != null) return source.displayName();
        return id == null || id.isEmpty() ? "-" : id;
    }

    private static boolean isPresent(ImportSource source) {
        try {
            return source.isPresent();
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
}
