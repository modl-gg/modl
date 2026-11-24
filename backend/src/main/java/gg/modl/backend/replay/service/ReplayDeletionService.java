package gg.modl.backend.replay.service;

import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.service.StorageDeletionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReplayDeletionService {
    private final ReplayRecordPurger replayRecordPurger;
    private final StorageDeletionService storageDeletionService;

    public long deleteReplaysWithStorage(Server server, List<ReplayDocument> replays) {
        if (replays.isEmpty()) {
            return 0L;
        }
        List<String> storageKeys = replays.stream()
            .map(ReplayDocument::getStorageKey)
            .filter(key -> key != null && !key.isBlank())
            .toList();
        storageDeletionService.delete(server, storageKeys);
        replayRecordPurger.purge(replays.stream().map(ReplayDocument::getId).toList());
        return replays.size();
    }
}
