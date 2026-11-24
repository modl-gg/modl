package gg.modl.backend.iplookup.service;

import gg.modl.backend.iplookup.repository.IpLookupRepository;
import gg.modl.backend.player.config.IpHistoryPolicy;
import gg.modl.backend.retention.RetentionBatches;
import gg.modl.backend.retention.service.RetentionTask;
import gg.modl.backend.server.data.Server;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IpLookupCacheTrimmer implements RetentionTask {
    public static final long MAX_ENTRIES = 100_000;

    private final IpLookupRepository repository;
    private final IpHistoryPolicy ipHistory;

    @Override
    public String subject() {
        return "cached IP lookups";
    }

    @Override
    public long purge(Server server, Instant now) {
        return dropOlderThanIpHistory(now) + dropOldestOverTheCap();
    }

    private long dropOlderThanIpHistory(Instant now) {
        Instant cutoff = ipHistory.cutoff(now);
        return RetentionBatches.drain(RetentionBatches.DEFAULT_BATCH_SIZE, RetentionBatches.DEFAULT_MAX_BATCHES_PER_RUN,
            size -> repository.deleteLookedUpBefore(cutoff, size));
    }

    private long dropOldestOverTheCap() {
        long excess = repository.count() - MAX_ENTRIES;
        if (excess <= 0) {
            return 0;
        }
        return RetentionBatches.drainAtMost(excess, RetentionBatches.DEFAULT_BATCH_SIZE,
            RetentionBatches.DEFAULT_MAX_BATCHES_PER_RUN, repository::deleteOldest);
    }
}
