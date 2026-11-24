package gg.modl.backend.log.repository;

import gg.modl.backend.retention.repository.AgeRetainedRecords;
import java.util.List;

public interface ChatLogRepository extends AgeRetainedRecords {
    void insertAll(List<ChatLogRow> rows);

    List<ChatLogRow> findRecentByPlayer(String playerId, int limit);
}
