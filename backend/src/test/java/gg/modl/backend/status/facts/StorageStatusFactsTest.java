package gg.modl.backend.status.facts;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.status.StatusLevel;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import org.junit.jupiter.api.Test;

class StorageStatusFactsTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void theDiskFactFollowsTheFloorThatRefusesWrites() throws IOException {
        long floor = DiskSpaceFloor.DEFAULT.minimumFreeBytes();

        assertThat(StorageStatusFacts.diskFree(new FixedStore(10 * GIB, floor - 1)).level()).isEqualTo(StatusLevel.PROBLEM);
        assertThat(StorageStatusFacts.diskFree(new FixedStore(10 * GIB, floor + GIB / 2)).level()).isEqualTo(StatusLevel.WARNING);
        assertThat(StorageStatusFacts.diskFree(new FixedStore(10 * GIB, 3 * floor)).level()).isEqualTo(StatusLevel.OK);
    }

    @Test
    void aLargeDiskUsesTheProportionalFloor() throws IOException {
        long total = 1000 * GIB;

        assertThat(StorageStatusFacts.diskFree(new FixedStore(total, 30 * GIB)).level()).isEqualTo(StatusLevel.PROBLEM);
    }

    private static final class FixedStore extends FileStore {
        private final long total;
        private final long usable;

        private FixedStore(long total, long usable) {
            this.total = total;
            this.usable = usable;
        }

        @Override
        public String name() {
            return "fixed";
        }

        @Override
        public String type() {
            return "fixed";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public long getTotalSpace() {
            return total;
        }

        @Override
        public long getUsableSpace() {
            return usable;
        }

        @Override
        public long getUnallocatedSpace() {
            return usable;
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
            return null;
        }
    }
}
