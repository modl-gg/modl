package gg.modl.minecraft.core.imports.source;

public interface SourceReader extends AutoCloseable {
    SourceInventory inventory() throws SourceException;

    long historyEntriesBefore(long epochMillis) throws SourceException;

    PlayerRecords players() throws SourceException;

    boolean isReusable();

    void cancelRunning();

    @Override
    void close();
}
