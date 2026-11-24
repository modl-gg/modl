package gg.modl.backend.storage.repository;

import static gg.modl.backend.database.jooq.Tables.STORAGE_FILES;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.StorageFilesRecord;
import gg.modl.backend.storage.UploadOrigin;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.SelectForUpdateStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqStorageFileRepository implements StorageFileRepository {
    private final DSLContext dsl;
    private final DatabaseDialect dialect;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;

    @Override
    public boolean insertIfAbsent(StorageFile file, UploadOrigin origin, @Nullable Instant unattachedExpiresAtOrNull) {
        StorageFilesRecord row = new StorageFilesRecord(
            TextLimits.require(STORAGE_FILES.STORAGE_KEY, file.key()),
            TextLimits.requireIfPresent(STORAGE_FILES.FILE_NAME, file.fileName()),
            file.size(),
            TextLimits.requireIfPresent(STORAGE_FILES.CONTENT_TYPE, file.contentType()),
            TextLimits.requireIfPresent(STORAGE_FILES.CATEGORY, file.category()),
            unattachedExpiresAtOrNull,
            file.createdAt(),
            TextLimits.require(STORAGE_FILES.ORIGIN, origin.name()));
        return unitOfWork.write(() -> insertIfAbsent.onPrimaryKey(STORAGE_FILES).insert(row));
    }

    @Override
    public int markAttached(Collection<String> storageKeys) {
        List<String> keys = StorageKeyQueries.storableKeys(STORAGE_FILES.STORAGE_KEY, storageKeys);
        if (keys.isEmpty()) {
            return 0;
        }
        return unitOfWork.write(() -> InClauseChunks.<String>sum(keys, chunk -> dsl.update(STORAGE_FILES)
            .setNull(STORAGE_FILES.UNATTACHED_EXPIRES_AT)
            .where(STORAGE_FILES.STORAGE_KEY.in(chunk))
            .execute()));
    }

    @Override
    public List<String> findUnattachedExpired(Instant now, int limit) {
        SelectForUpdateStep<Record1<String>> expired = dsl.select(STORAGE_FILES.STORAGE_KEY)
            .from(STORAGE_FILES)
            .where(STORAGE_FILES.UNATTACHED_EXPIRES_AT.lt(now))
            .orderBy(STORAGE_FILES.UNATTACHED_EXPIRES_AT, STORAGE_FILES.STORAGE_KEY)
            .limit(limit);
        return dialect.isExternal()
            ? expired.forUpdate().fetch(STORAGE_FILES.STORAGE_KEY)
            : expired.fetch(STORAGE_FILES.STORAGE_KEY);
    }

    @Override
    public Optional<StorageFile> findByKey(String key) {
        return findByKeys(List.of(key)).stream().findFirst();
    }

    @Override
    public List<StorageFile> findByKeys(Collection<String> keys) {
        List<String> storable = StorageKeyQueries.storableKeys(STORAGE_FILES.STORAGE_KEY, keys);
        return InClauseChunks.<String, StorageFile>collect(storable, chunk -> dsl.selectFrom(STORAGE_FILES)
            .where(STORAGE_FILES.STORAGE_KEY.in(chunk))
            .orderBy(STORAGE_FILES.STORAGE_KEY)
            .fetch(JooqStorageFileRepository::toFile));
    }

    @Override
    public boolean hasAnyFile() {
        return dsl.fetchExists(STORAGE_FILES);
    }

    @Override
    public void deleteByKeys(Collection<String> keys) {
        List<String> storable = StorageKeyQueries.storableKeys(STORAGE_FILES.STORAGE_KEY, keys);
        if (storable.isEmpty()) {
            return;
        }
        unitOfWork.write(() -> {
            InClauseChunks.<String>sum(storable, chunk -> dsl.deleteFrom(STORAGE_FILES)
                .where(STORAGE_FILES.STORAGE_KEY.in(chunk))
                .execute());
        });
    }

    @Override
    public long sumSizeSince(UploadOrigin origin, Instant createdAfter) {
        return StorageKeyQueries.sum(dsl, STORAGE_FILES.SIZE_BYTES,
            STORAGE_FILES.ORIGIN.eq(origin.name()).and(STORAGE_FILES.CREATED_AT.ge(createdAfter)));
    }

    @Override
    public PrefixUsage usageUnder(Collection<String> prefixes) {
        if (prefixes.isEmpty()) {
            return new PrefixUsage(0, 0);
        }
        return StorageKeyQueries.usage(dsl, STORAGE_FILES.SIZE_BYTES, StorageKeyQueries.startsWithAny(STORAGE_FILES.STORAGE_KEY, prefixes));
    }

    @Override
    public long sumTotalSize() {
        return StorageKeyQueries.sum(dsl, STORAGE_FILES.SIZE_BYTES, DSL.noCondition());
    }

    private static StorageFile toFile(StorageFilesRecord row) {
        return new StorageFile(row.getStorageKey(), row.getFileName(), row.getSizeBytes(), row.getContentType(),
            row.getCategory(), row.getCreatedAt());
    }
}
