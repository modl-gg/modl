package gg.modl.backend.storage.backend;

import static gg.modl.backend.database.jooq.Tables.STORAGE_TRANSFERS;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.StorageTransfersRecord;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.backend.StorageTransfer.TransferCounts;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqStorageTransferRepository implements StorageTransferRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    @Override
    public void insert(StorageTransfer transfer) {
        StorageTransfersRecord row = toRecord(transfer);
        unitOfWork.write(() -> {
            dsl.insertInto(STORAGE_TRANSFERS).set(row).execute();
        });
    }

    @Override
    public void save(StorageTransfer transfer) {
        StorageTransfersRecord row = toRecord(transfer);
        unitOfWork.write(() -> {
            dsl.update(STORAGE_TRANSFERS)
                .set(row)
                .where(STORAGE_TRANSFERS.ID.eq(row.getId()))
                .execute();
        });
    }

    @Override
    public boolean saveWhileRunning(StorageTransfer transfer) {
        StorageTransfersRecord row = toRecord(transfer);
        return unitOfWork.write(() -> dsl.update(STORAGE_TRANSFERS)
            .set(row)
            .where(STORAGE_TRANSFERS.ID.eq(row.getId()))
            .and(STORAGE_TRANSFERS.STATE.eq(StorageTransferState.RUNNING.name()))
            .execute() == 1);
    }

    @Override
    public Optional<StorageTransfer> latest() {
        return unitOfWork.read(() -> dsl.selectFrom(STORAGE_TRANSFERS)
            .orderBy(STORAGE_TRANSFERS.STARTED_AT.desc(), STORAGE_TRANSFERS.ID.desc())
            .limit(1)
            .fetchOptional(JooqStorageTransferRepository::toTransfer));
    }

    @Override
    public int deleteFinishedBeyondNewest(int kept) {
        return unitOfWork.write(() -> {
            List<String> retained = dsl.select(STORAGE_TRANSFERS.ID)
                .from(STORAGE_TRANSFERS)
                .orderBy(STORAGE_TRANSFERS.STARTED_AT.desc(), STORAGE_TRANSFERS.ID.desc())
                .limit(kept)
                .fetch(STORAGE_TRANSFERS.ID);
            return dsl.deleteFrom(STORAGE_TRANSFERS)
                .where(STORAGE_TRANSFERS.STATE.ne(StorageTransferState.RUNNING.name()))
                .and(retained.isEmpty() ? STORAGE_TRANSFERS.ID.isNotNull() : STORAGE_TRANSFERS.ID.notIn(retained))
                .execute();
        });
    }

    private static StorageTransfersRecord toRecord(StorageTransfer transfer) {
        TransferCounts counts = transfer.counts();
        StorageTransfersRecord row = new StorageTransfersRecord();
        row.setId(TextLimits.require(STORAGE_TRANSFERS.ID, transfer.id()));
        row.setSourceBackend(TextLimits.require(STORAGE_TRANSFERS.SOURCE_BACKEND, transfer.source().name()));
        row.setTargetBackend(TextLimits.require(STORAGE_TRANSFERS.TARGET_BACKEND, transfer.target().name()));
        row.setState(TextLimits.require(STORAGE_TRANSFERS.STATE, transfer.state().name()));
        row.setCopiedObjects(counts.copiedObjects());
        row.setCopiedBytes(counts.copiedBytes());
        row.setSkippedObjects(counts.skippedObjects());
        row.setFailedObjects(counts.failedObjects());
        row.setLastError(transfer.lastError());
        row.setStartedAt(transfer.startedAt());
        row.setUpdatedAt(transfer.updatedAt());
        row.setFinishedAt(transfer.finishedAt());
        return row;
    }

    private static StorageTransfer toTransfer(StorageTransfersRecord row) {
        return new StorageTransfer(
            row.getId(),
            StorageType.valueOf(row.getSourceBackend()),
            StorageType.valueOf(row.getTargetBackend()),
            StorageTransferState.valueOf(row.getState()),
            new TransferCounts(row.getCopiedObjects(), row.getCopiedBytes(), row.getSkippedObjects(), row.getFailedObjects()),
            row.getLastError(),
            row.getStartedAt(),
            row.getUpdatedAt(),
            row.getFinishedAt()
        );
    }
}
