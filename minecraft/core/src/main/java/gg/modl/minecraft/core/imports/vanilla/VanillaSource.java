package gg.modl.minecraft.core.imports.vanilla;

import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceReader;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RequiredArgsConstructor
public final class VanillaSource implements ImportSource {
    static final String BANNED_PLAYERS = "banned-players.json";
    static final String BANNED_IPS = "banned-ips.json";
    static final String ESSENTIALS_USERS = "userdata";
    private static final long EMPTY_LIST_BYTES = 4;

    private final SourceContext context;

    @Override
    public String id() {
        return "vanilla";
    }

    @Override
    public String displayName() {
        return "Vanilla and EssentialsX";
    }

    @Override
    public boolean isPresent() {
        Path root = context.getServerRoot();
        return hasEntries(root.resolve(BANNED_PLAYERS)) || hasEntries(root.resolve(BANNED_IPS))
                || Files.isDirectory(essentialsUsers());
    }

    @Override
    public SourceReader open() throws SourceException {
        if (!isPresent()) throw SourceException.missing("No ban lists or EssentialsX user data in " + context.getServerRoot());
        return new VanillaReader(context, context.getServerRoot(), essentialsUsers());
    }

    private Path essentialsUsers() {
        return context.pluginFolder("Essentials").resolve(ESSENTIALS_USERS);
    }

    private static boolean hasEntries(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > EMPTY_LIST_BYTES;
        } catch (IOException unreadable) {
            return false;
        }
    }
}
