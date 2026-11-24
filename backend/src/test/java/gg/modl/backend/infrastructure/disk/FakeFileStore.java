package gg.modl.backend.infrastructure.disk;

import java.nio.file.FileStore;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

public final class FakeFileStore extends FileStore {
    private final long totalBytes;
    private final long usableBytes;

    public FakeFileStore(long totalBytes, long usableBytes) {
        this.totalBytes = totalBytes;
        this.usableBytes = usableBytes;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public String type() {
        return "fake";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public long getTotalSpace() {
        return totalBytes;
    }

    @Override
    public long getUsableSpace() {
        return usableBytes;
    }

    @Override
    public long getUnallocatedSpace() {
        return usableBytes;
    }

    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
        return false;
    }

    @Override
    public boolean supportsFileAttributeView(String name) {
        return false;
    }

    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
        return null;
    }

    @Override
    public Object getAttribute(String attribute) {
        throw new UnsupportedOperationException(attribute);
    }
}
