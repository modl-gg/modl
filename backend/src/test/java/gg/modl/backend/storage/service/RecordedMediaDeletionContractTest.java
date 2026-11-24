package gg.modl.backend.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.StorageContractFixture;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class RecordedMediaDeletionContractTest {
    private static final String ARTICLE_KEY = "modl_instance/article/guide.png";
    private static final String EVIDENCE_KEY = "modl_instance/evidence/PUN-1/clip.mp4";
    private static final String REPLAY_KEY = "modl_instance/replays/R-1/replay.modlreplay";

    @TempDir
    Path directory;

    @SqlContractTest
    void contentMediaDeletionNeverReachesEvidenceReplaysOrUnrecordedKeys(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file(EVIDENCE_KEY, 10);
            storage.file(REPLAY_KEY, 10);
            storage.file("modl_instance/article/Guide.png", 10);
            StorageDeletionService deletion = storage.deletion();

            for (String key : List.of(EVIDENCE_KEY, REPLAY_KEY)) {
                assertThat(deletion.deleteRecordedMedia(storage.server(), key, MediaCategory.ARTICLE)).as(key)
                    .isEqualTo(MediaDeletion.WRONG_CATEGORY);
            }
            for (String key : List.of(ARTICLE_KEY, "modl_instance/article/GUIDE.PNG")) {
                assertThat(deletion.deleteRecordedMedia(storage.server(), key, MediaCategory.ARTICLE)).as(key)
                    .isEqualTo(MediaDeletion.NOT_FOUND);
            }

            verify(storage.objectStorage(), never()).deleteAll(anyCollection());
            assertThat(storage.hasFile(EVIDENCE_KEY)).isTrue();
            assertThat(storage.hasFile(REPLAY_KEY)).isTrue();
            assertThat(storage.hasFile("modl_instance/article/Guide.png")).isTrue();
        }
    }

    @SqlContractTest
    void aRecordedArticleObjectIsDeletedWithItsRecord(DialectTarget target) {
        try (StorageContractFixture storage = StorageContractFixture.start(target, directory)) {
            storage.file(ARTICLE_KEY, 10);

            assertThat(storage.deletion().deleteRecordedMedia(storage.server(), ARTICLE_KEY, MediaCategory.ARTICLE))
                .isEqualTo(MediaDeletion.DELETED);

            assertThat(storage.hasFile(ARTICLE_KEY)).isFalse();
            verify(storage.objectStorage()).deleteAll(List.of(ARTICLE_KEY));
        }
    }
}
