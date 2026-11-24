package gg.modl.backend.replay.service;

import static gg.modl.backend.database.jooq.Tables.REPLAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.infrastructure.validation.BeanValidationRunner;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.repository.JooqReplayRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.UploadTarget;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.storage.service.StorageMetadataService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;

class ReplayUploadContractTest {
    private static final long FILE_SIZE = 2048;
    private static final String SUPPLEMENTARY = new String(Character.toChars(0x10400));

    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anyAcceptedMinecraftVersionIsStoredWithinItsColumn(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            int limit = TextLimits.limit(REPLAYS.MC_VERSION);
            Map<String, String> storedBySubmitted = new LinkedHashMap<>();
            storedBySubmitted.put("1.21.11", "1.21.11");
            storedBySubmitted.put("v".repeat(limit), "v".repeat(limit));
            storedBySubmitted.put("v".repeat(limit + 1), "v".repeat(limit));
            storedBySubmitted.put("v".repeat(10_000), "v".repeat(limit));
            storedBySubmitted.put(SUPPLEMENTARY.repeat(limit), SUPPLEMENTARY.repeat(limit));
            storedBySubmitted.put(SUPPLEMENTARY.repeat(limit + 1), SUPPLEMENTARY.repeat(limit));
            storedBySubmitted.put("1.21\u0000.11", "1.21.11");
            ReplayService service = service(database);
            JooqReplayRepository repository = new JooqReplayRepository(database.dsl(), database.jsonColumns());

            storedBySubmitted.forEach((submitted, stored) -> {
                String replayId = service.initUpload(server, submitted, FILE_SIZE, null, null).replayId();

                assertThat(repository.findById(replayId)).get().extracting(ReplayDocument::getMcVersion).isEqualTo(stored);
            });
        }
    }

    private static ReplayService service(SqlTestDatabase database) {
        StorageLimitPolicy limits = mock(StorageLimitPolicy.class);
        when(limits.maxUploadBytes(MediaCategory.REPLAY)).thenReturn(FILE_SIZE);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.presignUpload(anyString(), anyString(), anyLong()))
            .thenReturn(new UploadTarget("/upload", "PUT", Map.of(), SqlTestDatabase.START.plusSeconds(600)));
        return new ReplayService(new JooqReplayRepository(database.dsl(), database.jsonColumns()), storage,
            mock(StorageMetadataService.class), mock(StorageDeletionService.class), limits,
            mock(BeanValidationRunner.class), database.unitOfWork(), database.clock());
    }
}
