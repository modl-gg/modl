package gg.modl.backend.storage.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.UploadLimits;
import gg.modl.backend.storage.repository.PrefixUsage;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.support.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageUsageReportTest {
    private static final long GB = UploadLimits.BYTES_PER_GB;

    @TempDir
    Path dataDirectory;

    private final StorageFileRepository files = mock(StorageFileRepository.class);
    private final UnitOfWork unitOfWork = mock(UnitOfWork.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));

    @BeforeEach
    void passUnitsThrough() {
        when(unitOfWork.report(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        when(files.usageUnder(anyCollection())).thenAnswer(invocation -> usageFor(invocation.getArgument(0)));
    }

    @Test
    void eachGroupReportsItsFilesAndTheRestIsOther() {
        when(files.sumTotalSize()).thenReturn(1_000L);

        StorageUsage usage = report(UploadLimits.of(100, 0)).current(StorageType.S3);

        assertThat(usage.groups()).extracting(StorageUsage.GroupUsage::group).containsExactly(StorageUsageGroup.values());
        assertThat(usage.groups()).extracting(StorageUsage.GroupUsage::bytes).containsExactly(100L, 20L, 30L, 40L, 50L, 760L);
        assertThat(usage.groups()).extracting(StorageUsage.GroupUsage::files).containsExactly(1L, 2L, 3L, 4L, 5L, 0L);
        assertThat(usage.usedBytes()).isEqualTo(1_000L);
    }

    @Test
    void otherNeverGoesNegativeWhenTheTotalLagsBehindTheGroups() {
        when(files.sumTotalSize()).thenReturn(10L);

        StorageUsage usage = report(UploadLimits.of(100, 0)).current(StorageType.S3);

        assertThat(usage.groups().getLast()).isEqualTo(new StorageUsage.GroupUsage(StorageUsageGroup.OTHER, 0L, 0L));
    }

    @Test
    void theGroupsAndTheTotalAreOneSnapshotCachedForAMinute() {
        when(files.sumTotalSize()).thenReturn(1_000L, 2_000L);
        StorageUsageReport report = report(UploadLimits.of(100, 0));
        int groupQueries = StorageUsageGroup.values().length - 1;

        report.current(StorageType.S3);
        clock.advance(Duration.ofSeconds(59));
        StorageUsage cached = report.current(StorageType.S3);
        verify(files, times(groupQueries)).usageUnder(anyCollection());
        verify(files, times(1)).sumTotalSize();
        clock.advance(Duration.ofSeconds(1));
        StorageUsage refreshed = report.current(StorageType.S3);

        verify(files, times(2 * groupQueries)).usageUnder(anyCollection());
        assertThat(cached.usedBytes()).isEqualTo(1_000L);
        assertThat(cached.groups().getLast().bytes()).isEqualTo(760L);
        assertThat(refreshed.usedBytes()).isEqualTo(2_000L);
        assertThat(refreshed.groups().getLast().bytes()).isEqualTo(1_760L);
    }

    @Test
    void s3CapacityIsTheStorageLimitOrUnknownWithoutOne() {
        when(files.sumTotalSize()).thenReturn(1_000L);

        assertThat(report(UploadLimits.of(100, 5)).current(StorageType.S3).capacityBytes()).isEqualTo(5 * GB);
        assertThat(report(UploadLimits.of(100, 0)).current(StorageType.S3).capacityBytes()).isZero();
    }

    @Test
    void localCapacityIsTheUsedBytesPlusTheFreeDiskCappedByTheLimit() {
        when(files.sumTotalSize()).thenReturn(1_000L);

        long unlimited = report(UploadLimits.of(100, 0)).current(StorageType.LOCAL).capacityBytes();
        long limited = report(UploadLimits.of(100, 1)).current(StorageType.LOCAL).capacityBytes();

        assertThat(unlimited).isGreaterThan(1_000L);
        assertThat(limited).isEqualTo(Math.min(GB, unlimited));
    }

    @Test
    void localCapacityLeavesTheFreeSpaceFloorOut() {
        when(files.sumTotalSize()).thenReturn(1_000L);

        long withoutFloor = report(UploadLimits.of(100, 0), new DiskSpaceFloor(0L, 0.0)).current(StorageType.LOCAL).capacityBytes();
        long beyondTheDisk = report(UploadLimits.of(100, 0), new DiskSpaceFloor(Long.MAX_VALUE, 0.0)).current(StorageType.LOCAL)
            .capacityBytes();

        assertThat(withoutFloor).isGreaterThan(1_000L);
        assertThat(beyondTheDisk).isEqualTo(1_000L);
    }

    private StorageUsageReport report(UploadLimits limits) {
        return report(limits, new DiskSpaceFloor(0L, 0.0));
    }

    private StorageUsageReport report(UploadLimits limits, DiskSpaceFloor floor) {
        return new StorageUsageReport(files, unitOfWork, limits,
            new DataDirectoryProperties(dataDirectory.resolve("not-created-yet").toString()), floor, clock);
    }

    private static PrefixUsage usageFor(Collection<String> prefixes) {
        List<String> requested = List.copyOf(prefixes);
        StorageUsageGroup group = Arrays.stream(StorageUsageGroup.values())
            .filter(candidate -> candidate.keyPrefixes().equals(requested))
            .findFirst()
            .orElseThrow();
        return switch (group) {
            case REPLAYS -> new PrefixUsage(1, 100);
            case EVIDENCE -> new PrefixUsage(2, 20);
            case TICKETS -> new PrefixUsage(3, 30);
            case ARTICLES -> new PrefixUsage(4, 40);
            case ICONS -> new PrefixUsage(5, 50);
            case OTHER -> new PrefixUsage(0, 0);
        };
    }
}
