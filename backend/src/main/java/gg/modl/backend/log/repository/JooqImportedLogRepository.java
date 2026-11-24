package gg.modl.backend.log.repository;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static gg.modl.backend.database.jooq.Tables.COMMAND_LOGS;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.AuditLogsRecord;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.TableField;
import org.springframework.stereotype.Repository;

@Repository
public class JooqImportedLogRepository implements ImportedLogRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final JsonColumns json;

    public JooqImportedLogRepository(DSLContext dsl, UnitOfWork unitOfWork, JsonColumns json) {
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
        this.json = json;
    }

    @Override
    public Set<Long> takenIds(ImportedLogTable table, Collection<Long> ids) {
        TableField<?, Long> id = idOf(table);
        return unitOfWork.read(() -> new HashSet<>(InClauseChunks.collect(ids,
            chunk -> dsl.select(id).from(id.getTable()).where(id.in(chunk)).fetch(id))));
    }

    @Override
    public void insertAudit(List<ImportedAuditEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        List<AuditLogsRecord> rows = entries.stream()
            .sorted(Comparator.comparingLong(ImportedAuditEntry::id))
            .map(this::toRecord)
            .toList();
        unitOfWork.write(() -> {
            dsl.batchInsert(rows).execute();
        });
    }

    private AuditLogsRecord toRecord(ImportedAuditEntry entry) {
        return new AuditLogsRecord(
            entry.id(),
            TextLimits.require(AUDIT_LOGS.LEVEL, entry.level()),
            TextLimits.require(AUDIT_LOGS.SOURCE, entry.source()),
            entry.description(),
            entry.metadata().isEmpty() ? null : json.write(entry.metadata()),
            entry.canRollback(),
            entry.createdAt());
    }

    private static TableField<?, Long> idOf(ImportedLogTable table) {
        return switch (table) {
            case CHAT -> CHAT_LOGS.ID;
            case COMMAND -> COMMAND_LOGS.ID;
            case AUDIT -> AUDIT_LOGS.ID;
        };
    }
}
