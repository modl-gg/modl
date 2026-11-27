package gg.modl.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StorageFoldersTest {
    @Test
    void replayKeysAreNeverServedHoweverTheyAreSpelled() {
        assertThat(StorageFolders.servedByStorageUrl("modl_instance/replays/abc.modlreplay")).isFalse();
        assertThat(StorageFolders.servedByStorageUrl("modl_instance//replays/abc.modlreplay")).isFalse();
        assertThat(StorageFolders.servedByStorageUrl("modl_instance/./replays/abc.modlreplay")).isFalse();
        assertThat(StorageFolders.servedByStorageUrl("/modl_instance/replays/abc.modlreplay")).isFalse();
        assertThat(StorageFolders.servedByStorageUrl("modl_instance/REPLAYS/abc.modlreplay")).isFalse();
        assertThat(StorageFolders.servedByStorageUrl("modl_instance/evidence/../replays/abc.modlreplay")).isFalse();
    }

    @Test
    void keysThatAreNotCanonicalAreNeverServed() {
        assertThat(StorageFolders.servedByStorageUrl("modl_instance/./evidence/abc.png")).isFalse();
        assertThat(StorageFolders.servedByStorageUrl("modl_instance//evidence/abc.png")).isFalse();
        assertThat(StorageFolders.servedByStorageUrl("modl_instance/evidence/")).isFalse();
    }

    @Test
    void otherFoldersAreServed() {
        assertThat(StorageFolders.servedByStorageUrl("modl_instance/evidence/abc.png")).isTrue();
        assertThat(StorageFolders.servedByStorageUrl("modl_instance/icons_panel/icon.png")).isTrue();
    }
}
