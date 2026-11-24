package gg.modl.backend.log.repository;

import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.ChatLogsRecord;
import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqChatLogRepository implements ChatLogRepository {
    private final PlayerLogTable<ChatLogsRecord, ChatLogRow> table;

    public JooqChatLogRepository(DSLContext dsl, UnitOfWork unitOfWork) {
        this.table = new PlayerLogTable<>(dsl, unitOfWork, CHAT_LOGS.ID, CHAT_LOGS.PLAYER_ID, CHAT_LOGS.SENT_AT,
            JooqChatLogRepository::toRecord, JooqChatLogRepository::toRow);
    }

    @Override
    public void insertAll(List<ChatLogRow> rows) {
        table.insertAll(rows);
    }

    @Override
    public List<ChatLogRow> findRecentByPlayer(String playerId, int limit) {
        return table.findRecentByPlayer(playerId, limit);
    }

    @Override
    public long deleteOldestBatch(Instant createdBefore, int batchSize) {
        return table.deleteOldestBatch(createdBefore, batchSize);
    }

    private static ChatLogsRecord toRecord(ChatLogRow row) {
        return new ChatLogsRecord(
            row.id(),
            TextLimits.require(CHAT_LOGS.PLAYER_ID, row.playerId()),
            TextLimits.requireIfPresent(CHAT_LOGS.USERNAME, row.username()),
            row.message(),
            TextLimits.require(CHAT_LOGS.SERVER_NAME, row.serverName()),
            row.sentAt());
    }

    private static ChatLogRow toRow(ChatLogsRecord row) {
        return new ChatLogRow(row.getId(), row.getPlayerId(), row.getUsername(), row.getMessage(), row.getServerName(),
            row.getSentAt());
    }
}
