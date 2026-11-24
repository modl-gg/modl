package gg.modl.backend.storage.repository;

import static gg.modl.backend.database.jooq.Tables.STORAGE_RESERVATIONS;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.StorageReservationsRecord;
import gg.modl.backend.storage.ReservedBytes;
import gg.modl.backend.storage.UploadOrigin;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqStorageReservationRepository implements StorageReservationRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    @Override
    public void reserve(StorageReservation reservation) {
        StorageReservationsRecord row = new StorageReservationsRecord(
            TextLimits.require(STORAGE_RESERVATIONS.STORAGE_KEY, reservation.key()),
            reservation.bytes(),
            reservation.landed(),
            reservation.settleAfter(),
            reservation.createdAt(),
            TextLimits.require(STORAGE_RESERVATIONS.ORIGIN, reservation.origin().name()));
        unitOfWork.write(() -> {
            dsl.insertInto(STORAGE_RESERVATIONS).set(row).execute();
        });
    }

    @Override
    public Optional<StorageReservation> claim(String key) {
        if (!StorageKeyQueries.canBeStored(STORAGE_RESERVATIONS.STORAGE_KEY, key)) {
            return Optional.empty();
        }
        String bound = TextLimits.require(STORAGE_RESERVATIONS.STORAGE_KEY, key);
        return unitOfWork.write(() -> dsl.selectFrom(STORAGE_RESERVATIONS)
            .where(STORAGE_RESERVATIONS.STORAGE_KEY.eq(bound))
            .fetchOptional(JooqStorageReservationRepository::toReservation)
            .filter(reservation -> dsl.deleteFrom(STORAGE_RESERVATIONS)
                .where(STORAGE_RESERVATIONS.STORAGE_KEY.eq(bound))
                .execute() == 1));
    }

    @Override
    public boolean markLanded(String key) {
        if (!StorageKeyQueries.canBeStored(STORAGE_RESERVATIONS.STORAGE_KEY, key)) {
            return false;
        }
        String bound = TextLimits.require(STORAGE_RESERVATIONS.STORAGE_KEY, key);
        return unitOfWork.write(() -> dsl.update(STORAGE_RESERVATIONS)
            .set(STORAGE_RESERVATIONS.IS_LANDED, true)
            .where(STORAGE_RESERVATIONS.STORAGE_KEY.eq(bound))
            .and(STORAGE_RESERVATIONS.IS_LANDED.eq(false))
            .execute() == 1);
    }

    @Override
    public ReservedBytes sumReserved() {
        Field<BigDecimal> total = DSL.coalesce(DSL.sum(STORAGE_RESERVATIONS.RESERVED_BYTES), BigDecimal.ZERO);
        Field<BigDecimal> inFlight = DSL.coalesce(
            DSL.sum(DSL.when(STORAGE_RESERVATIONS.IS_LANDED.eq(false), STORAGE_RESERVATIONS.RESERVED_BYTES)), BigDecimal.ZERO);
        Record2<BigDecimal, BigDecimal> sums = dsl.select(total, inFlight).from(STORAGE_RESERVATIONS).fetchSingle();
        return new ReservedBytes(sums.value1().longValue(), sums.value2().longValue());
    }

    @Override
    public long sumReservedBytes(UploadOrigin origin) {
        return StorageKeyQueries.sum(dsl, STORAGE_RESERVATIONS.RESERVED_BYTES, STORAGE_RESERVATIONS.ORIGIN.eq(origin.name()));
    }

    @Override
    public PrefixUsage usageUnder(String prefix) {
        return StorageKeyQueries.usage(dsl, STORAGE_RESERVATIONS.RESERVED_BYTES,
            StorageKeyQueries.startsWithAny(STORAGE_RESERVATIONS.STORAGE_KEY, List.of(prefix)));
    }

    @Override
    public List<String> findSettleableKeys(Instant now, int limit) {
        return dsl.select(STORAGE_RESERVATIONS.STORAGE_KEY)
            .from(STORAGE_RESERVATIONS)
            .where(STORAGE_RESERVATIONS.SETTLE_AFTER_AT.lt(now))
            .orderBy(STORAGE_RESERVATIONS.SETTLE_AFTER_AT, STORAGE_RESERVATIONS.STORAGE_KEY)
            .limit(limit)
            .fetch(STORAGE_RESERVATIONS.STORAGE_KEY);
    }

    @Override
    public Set<String> findReservedKeys(Collection<String> keys) {
        List<String> storable = StorageKeyQueries.storableKeys(STORAGE_RESERVATIONS.STORAGE_KEY, keys);
        return Set.copyOf(InClauseChunks.<String, String>collect(storable, chunk -> dsl.select(STORAGE_RESERVATIONS.STORAGE_KEY)
            .from(STORAGE_RESERVATIONS)
            .where(STORAGE_RESERVATIONS.STORAGE_KEY.in(chunk))
            .fetch(STORAGE_RESERVATIONS.STORAGE_KEY)));
    }

    @Override
    public long release(Collection<String> keys) {
        List<String> storable = StorageKeyQueries.storableKeys(STORAGE_RESERVATIONS.STORAGE_KEY, keys);
        if (storable.isEmpty()) {
            return 0L;
        }
        return unitOfWork.write(() -> InClauseChunks.<String>sum(storable, chunk -> dsl.deleteFrom(STORAGE_RESERVATIONS)
            .where(STORAGE_RESERVATIONS.STORAGE_KEY.in(chunk))
            .execute())).longValue();
    }

    @Override
    public boolean anyCreatedBefore(Instant cutoff) {
        return unitOfWork.read(() -> dsl.fetchExists(dsl.selectOne()
            .from(STORAGE_RESERVATIONS)
            .where(STORAGE_RESERVATIONS.CREATED_AT.lt(cutoff))));
    }

    private static StorageReservation toReservation(StorageReservationsRecord row) {
        return new StorageReservation(row.getStorageKey(), row.getReservedBytes(), row.getIsLanded(), row.getSettleAfterAt(),
            row.getCreatedAt(), UploadOrigin.valueOf(row.getOrigin()));
    }
}
