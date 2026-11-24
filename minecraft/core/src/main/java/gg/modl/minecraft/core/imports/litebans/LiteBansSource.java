package gg.modl.minecraft.core.imports.litebans;

import gg.modl.minecraft.api.DatabaseProvider;
import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceReader;
import lombok.RequiredArgsConstructor;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class LiteBansSource implements ImportSource {
    private static final String MISSING = "import.litebans_missing";

    private final BooleanSupplier enabled;
    private final ProviderFactory providers;
    private final Supplier<String> defaultReason;
    private final LongSupplier clock;

    @Override
    public String id() {
        return "litebans";
    }

    @Override
    public String displayName() {
        return "LiteBans";
    }

    @Override
    public boolean isPresent() {
        return enabled.getAsBoolean();
    }

    @Override
    public SourceReader open() throws SourceException {
        if (!isPresent()) throw SourceException.missing(MISSING, "LiteBans is not running");
        DatabaseProvider database;
        try {
            database = providers.open();
        } catch (Exception | LinkageError unavailable) {
            throw SourceException.failed(unavailable);
        }
        return new LiteBansReader(database, defaultReason.get(), clock, enabled);
    }

    @FunctionalInterface
    public interface ProviderFactory {
        DatabaseProvider open() throws Exception;
    }
}
