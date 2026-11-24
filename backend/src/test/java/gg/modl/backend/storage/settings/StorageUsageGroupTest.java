package gg.modl.backend.storage.settings;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.ImportTestContexts;
import gg.modl.backend.hostedimport.storage.ImportedKeyPlan;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.StorageFolders;
import gg.modl.backend.storage.service.StorageKeyUtils;
import gg.modl.backend.support.TestServers;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageUsageGroupTest {
    private static final Server SERVER = TestServers.server("Server", "owner@example.com");

    @Test
    void everyMediaUploadLandsInItsOwnGroup() {
        for (MediaCategory category : MediaCategory.values()) {
            StorageUsageGroup expected = StorageUsageGroup.of(category);
            String loose = StorageKeyUtils.buildKey(SERVER, category.uploadType(), "file.png", null);
            String attached = StorageKeyUtils.buildKey(SERVER, category.uploadType(), "file.png", "entity");

            assertThat(groupsOf(loose)).as(loose).containsExactly(expected);
            assertThat(groupsOf(attached)).as(attached).containsExactly(expected);
        }
    }

    @Test
    void replaysPanelIconsAndTheThemeBackgroundLandInTheirGroups() {
        String replay = StorageKeyUtils.buildKey(SERVER, StorageFolders.REPLAYS, "replay.modlreplay", null);
        String homepageIcon = StorageKeyUtils.buildKey(SERVER, StorageFolders.icon("homepage"), "icon.png", null);
        String panelIcon = StorageKeyUtils.buildKey(SERVER, StorageFolders.icon("panel"), "icon.png", null);

        assertThat(groupsOf(replay)).containsExactly(StorageUsageGroup.REPLAYS);
        assertThat(groupsOf(homepageIcon)).containsExactly(StorageUsageGroup.ICONS);
        assertThat(groupsOf(panelIcon)).containsExactly(StorageUsageGroup.ICONS);
        assertThat(groupsOf(StorageKeyUtils.buildKey(SERVER, StorageFolders.THEME_BACKGROUND, "background.png", null)))
            .containsExactly(StorageUsageGroup.ICONS);
    }

    @Test
    void everyGroupButOtherHasPrefixesAndOtherHasNone() {
        assertThat(StorageUsageGroup.OTHER.keyPrefixes()).isEmpty();
        assertThat(Arrays.stream(StorageUsageGroup.values()).filter(group -> group != StorageUsageGroup.OTHER))
            .allSatisfy(group -> assertThat(group.keyPrefixes()).isNotEmpty());
    }

    @Test
    void importedHostedFilesLandInTheGroupOfTheirFolder(@TempDir Path directory) throws IOException {
        Map<String, StorageUsageGroup> expected = Map.of(
            "evidence/e1/a.png", StorageUsageGroup.EVIDENCE,
            "tickets/t1/a.png", StorageUsageGroup.TICKETS,
            "appeals/p1/a.png", StorageUsageGroup.TICKETS,
            "articles/a.png", StorageUsageGroup.ARTICLES,
            "server-icons/a.png", StorageUsageGroup.ICONS,
            "replays/a.png", StorageUsageGroup.REPLAYS);
        HostedArchiveFixture fixture = new HostedArchiveFixture();
        expected.keySet().forEach(key -> fixture.object(key, key));

        ImportedKeyPlan plan = ImportTestContexts.index(fixture, directory).keyPlan();

        assertThat(plan.objects()).hasSize(expected.size()).allSatisfy(object -> assertThat(groupsOf(object.localKey()))
            .as(object.localKey())
            .containsExactly(expected.get(object.source().key().substring(HostedArchiveFixture.PREFIX.length()))));
    }

    @Test
    void anUnknownFolderBelongsToNoGroup() {
        assertThat(groupsOf("modl_instance/logs/file.txt")).isEmpty();
    }

    private static List<StorageUsageGroup> groupsOf(String key) {
        return Arrays.stream(StorageUsageGroup.values())
            .filter(group -> group.keyPrefixes().stream().anyMatch(key::startsWith))
            .toList();
    }
}
