package gg.modl.minecraft.core.imports.source;

public interface ImportSource {
    String id();

    String displayName();

    boolean isPresent();

    SourceReader open() throws SourceException;
}
