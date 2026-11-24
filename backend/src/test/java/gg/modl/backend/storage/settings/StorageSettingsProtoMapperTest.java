package gg.modl.backend.storage.settings;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.backend.StorageBackendView;
import gg.modl.proto.modl.v1.StorageSettingsResponse;
import gg.modl.proto.modl.v1.StorageUsageGroupKind;
import gg.modl.proto.modl.v1.StorageUsageGroupView;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StorageSettingsProtoMapperTest {
    @Test
    void theUsageBreakdownKeepsEveryGroupInOrderWithTheTotals() {
        StorageUsageGroup[] kinds = StorageUsageGroup.values();
        List<StorageUsage.GroupUsage> groups = IntStream.range(0, kinds.length)
            .mapToObj(index -> new StorageUsage.GroupUsage(kinds[index], index, 10L * index))
            .toList();
        StorageBackendView backend = new StorageBackendView(StorageType.LOCAL, false, StorageProperties.S3.EMPTY,
            Optional.empty(), Optional.empty(), List.of(), false);

        StorageSettingsResponse response = StorageSettingsProtoMapper.toResponse(List.of(), 3, backend,
            new StorageUsage(groups, 150L, 4_096L));

        assertThat(response.getUsedBytes()).isEqualTo(150L);
        assertThat(response.getCapacityBytes()).isEqualTo(4_096L);
        assertThat(response.getUsageList()).extracting(StorageUsageGroupView::getGroup).containsExactly(
            StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_REPLAYS,
            StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_EVIDENCE,
            StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_TICKETS,
            StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_ARTICLES,
            StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_ICONS,
            StorageUsageGroupKind.STORAGE_USAGE_GROUP_KIND_OTHER);
        assertThat(response.getUsageList()).extracting(StorageUsageGroupView::getBytes).containsExactly(0L, 10L, 20L, 30L, 40L, 50L);
        assertThat(response.getUsageList()).extracting(StorageUsageGroupView::getFiles).containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
    }
}
