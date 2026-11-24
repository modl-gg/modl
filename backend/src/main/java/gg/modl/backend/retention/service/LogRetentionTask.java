package gg.modl.backend.retention.service;

import gg.modl.backend.retention.RetentionBatches;
import gg.modl.backend.retention.repository.AgeRetainedRecords;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.DataRetentionSettings;
import gg.modl.backend.settings.service.DataRetentionSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.function.ToIntFunction;

public final class LogRetentionTask implements RetentionTask {
    private final String subject;
    private final AgeRetainedRecords records;
    private final ToIntFunction<DataRetentionSettings> retentionDays;
    private final DataRetentionSettingsService settingsService;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public LogRetentionTask(
        String subject,
        AgeRetainedRecords records,
        ToIntFunction<DataRetentionSettings> retentionDays,
        DataRetentionSettingsService settingsService,
        int batchSize,
        int maxBatchesPerRun
    ) {
        this.subject = subject;
        this.records = records;
        this.retentionDays = retentionDays;
        this.settingsService = settingsService;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    @Override
    public String subject() {
        return subject;
    }

    @Override
    public long purge(Server server, Instant now) {
        int days = retentionDays.applyAsInt(settingsService.getDataRetentionSettings(server));
        Instant cutoff = now.minus(Duration.ofDays(days));
        return RetentionBatches.drain(batchSize, maxBatchesPerRun, limit -> records.deleteOldestBatch(cutoff, limit));
    }
}
