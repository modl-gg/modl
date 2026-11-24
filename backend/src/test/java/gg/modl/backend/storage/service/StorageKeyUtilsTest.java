package gg.modl.backend.storage.service;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StorageKeyUtilsTest {
    private final Server server = TestServers.server("server", "admin@example.com");

    @Test
    void entitySegmentsNeverStartWithADot() {
        String key = StorageKeyUtils.buildKey(server, "..evidence", ".hidden.png", "..PUN-1");

        assertThat(key.split("/")).allMatch(segment -> !segment.startsWith("."));
        assertThat(key).startsWith("modl_instance/evidence/PUN-1/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"new", "NEW", "New", " new "})
    void everySpellingOfTheAnonymousEntityLandsUnderOneSharedFolder(String entityId) {
        String ticketKey = StorageKeyUtils.buildKey(server, "ticket", "shot.png", entityId);
        String appealKey = StorageKeyUtils.buildKey(server, "appeal", "shot.png", entityId);

        assertThat(ticketKey).startsWith("modl_instance/tickets/new/");
        assertThat(appealKey).startsWith("modl_instance/appeal/new/");
    }

    @Test
    void windowsDeviceNamesAreMappedToSafeSegments() {
        String key = StorageKeyUtils.buildKey(server, "evidence", "CON.png", "nul");

        assertThat(key).startsWith("modl_instance/evidence/_nul/").endsWith("-_CON.png");
        assertThat(StorageKeyUtils.buildKey(server, "aux", "file.png", null)).startsWith("modl_instance/_aux/");
    }

    @Test
    void entityLessKeysLiveUnderTheirFolderPrefix() {
        String key = StorageKeyUtils.buildKey(server, "icons/homepage", "icon.png", null);

        assertThat(key).startsWith(StorageKeyUtils.folderPrefix(server, "icons/homepage")).endsWith(".png");
        assertThat(StorageKeyUtils.folderPrefix(server, "icons/homepage")).isEqualTo("modl_instance/icons_homepage/");
    }
}
