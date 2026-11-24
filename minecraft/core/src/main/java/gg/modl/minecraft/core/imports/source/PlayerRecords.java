package gg.modl.minecraft.core.imports.source;

import org.jspecify.annotations.Nullable;

public interface PlayerRecords {
    @Nullable SourcePlayer next() throws SourceException, UnreadablePlayerException;
}
