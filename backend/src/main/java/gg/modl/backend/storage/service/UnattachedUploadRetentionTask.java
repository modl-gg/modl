package gg.modl.backend.storage.service;

import gg.modl.backend.retention.RetentionBatches;
import gg.modl.backend.retention.service.RetentionTask;
import gg.modl.backend.server.data.Server;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UnattachedUploadRetentionTask implements RetentionTask {
    private final StorageDeletionService storageDeletionService;

    @Override
    public String subject() {
        return "uploads never submitted";
    }

    @Override
    public long purge(Server server, Instant now) {
        return RetentionBatches.drain(RetentionBatches.DEFAULT_BATCH_SIZE, RetentionBatches.DEFAULT_MAX_BATCHES_PER_RUN,
            limit -> storageDeletionService.deleteUnattachedExpired(server, now, limit));
    }
}
