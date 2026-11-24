package gg.modl.backend.log.repository;

import gg.modl.backend.retention.repository.AgeRetainedRecords;
import java.util.List;

public interface CommandLogRepository extends AgeRetainedRecords {
    void insertAll(List<CommandLogRow> rows);

    List<CommandLogRow> findRecentByPlayer(String playerId, int limit);
}
