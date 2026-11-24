package gg.modl.backend.replay.service;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.service.StorageKeyUtils;
import gg.modl.backend.storage.service.StoredObjectDeletionListener;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReplayRecordPurger implements StoredObjectDeletionListener {
    private static final String REPLAY_STORAGE_CATEGORY = "replay";

    private final ReplayRepository replayRepository;
    private final TicketRepository ticketRepository;
    private final UnitOfWork unitOfWork;

    @Override
    public void onStoredObjectsDeleted(Server server, Collection<String> keys) {
        List<String> replayKeys = keys.stream()
            .filter(key -> key != null && REPLAY_STORAGE_CATEGORY.equals(StorageKeyUtils.categorizeFile(key)))
            .toList();
        if (replayKeys.isEmpty()) {
            return;
        }
        List<String> replayIds = replayRepository.findByStorageKeys(replayKeys).stream()
            .map(ReplayDocument::getId)
            .toList();
        long removed = purge(replayIds);
        if (removed > 0) {
            log.info("Removed {} replay record(s) whose stored file was deleted", removed);
        }
    }

    long purge(List<String> replayIds) {
        long removed = 0L;
        for (List<String> chunk : InClauseChunks.<String>of(replayIds.stream().sorted().toList())) {
            removed += unitOfWork.write(() -> purgeChunk(chunk));
        }
        return removed;
    }

    private long purgeChunk(List<String> replayIds) {
        long removed = replayRepository.deleteByIds(replayIds);
        ticketRepository.clearReplayReferences(replayIds);
        return removed;
    }
}
