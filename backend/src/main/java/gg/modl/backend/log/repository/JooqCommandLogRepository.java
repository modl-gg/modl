package gg.modl.backend.log.repository;

import static gg.modl.backend.database.jooq.Tables.COMMAND_LOGS;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.CommandLogsRecord;
import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqCommandLogRepository implements CommandLogRepository {
    private final PlayerLogTable<CommandLogsRecord, CommandLogRow> table;

    public JooqCommandLogRepository(DSLContext dsl, UnitOfWork unitOfWork) {
        this.table = new PlayerLogTable<>(dsl, unitOfWork, COMMAND_LOGS.ID, COMMAND_LOGS.PLAYER_ID, COMMAND_LOGS.SENT_AT,
            JooqCommandLogRepository::toRecord, JooqCommandLogRepository::toRow);
    }

    @Override
    public void insertAll(List<CommandLogRow> rows) {
        table.insertAll(rows);
    }

    @Override
    public List<CommandLogRow> findRecentByPlayer(String playerId, int limit) {
        return table.findRecentByPlayer(playerId, limit);
    }

    @Override
    public long deleteOldestBatch(Instant createdBefore, int batchSize) {
        return table.deleteOldestBatch(createdBefore, batchSize);
    }

    private static CommandLogsRecord toRecord(CommandLogRow row) {
        return new CommandLogsRecord(
            row.id(),
            TextLimits.require(COMMAND_LOGS.PLAYER_ID, row.playerId()),
            TextLimits.requireIfPresent(COMMAND_LOGS.USERNAME, row.username()),
            row.command(),
            TextLimits.require(COMMAND_LOGS.SERVER_NAME, row.serverName()),
            row.sentAt());
    }

    private static CommandLogRow toRow(CommandLogsRecord row) {
        return new CommandLogRow(row.getId(), row.getPlayerId(), row.getUsername(), row.getCommand(), row.getServerName(),
            row.getSentAt());
    }
}
