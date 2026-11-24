package gg.modl.backend.player.service;

import gg.modl.backend.player.config.IpHistoryPolicy;
import gg.modl.backend.player.repository.IpHistoryKey;
import gg.modl.backend.player.repository.IpPruneBatch;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.retention.RetentionBatches;
import gg.modl.backend.retention.service.RetentionTask;
import gg.modl.backend.server.data.Server;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IpHistoryRetentionTask implements RetentionTask {
    private final PlayerRepository playerRepository;
    private final IpHistoryPolicy ipHistory;

    @Override
    public String subject() {
        return "IP history entries";
    }

    @Override
    public long purge(Server server, Instant now) {
        Instant cutoff = ipHistory.cutoff(now);
        long removed = 0;
        IpHistoryKey after = null;
        for (int batch = 0; batch < RetentionBatches.DEFAULT_MAX_BATCHES_PER_RUN; batch++) {
            IpPruneBatch pruned = playerRepository.pruneIpsUnseenSince(cutoff, now, after, RetentionBatches.DEFAULT_BATCH_SIZE);
            removed += pruned.deleted();
            Optional<IpHistoryKey> next = pruned.next();
            if (next.isEmpty()) {
                break;
            }
            after = next.get();
        }
        return removed;
    }
}
