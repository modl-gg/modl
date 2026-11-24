package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;

import gg.modl.backend.database.UnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Row2;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqRetainedIpHistoryRepository implements RetainedIpHistoryRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    @Override
    public Optional<Instant> latestRetainedSighting() {
        return unitOfWork.read(() -> dsl.select(DSL.max(PLAYER_IPS.LAST_SEEN_AT))
            .from(PLAYER_IPS)
            .where(PLAYER_IPS.RETAINED_UNTIL_AT.isNotNull())
            .fetchOptional(Record1::value1));
    }

    @Override
    public boolean anyRetained() {
        return unitOfWork.read(() -> dsl.fetchExists(PLAYER_IPS, PLAYER_IPS.RETAINED_UNTIL_AT.isNotNull()));
    }

    @Override
    public int releaseBatch(int limit) {
        List<Row2<String, String>> held = unitOfWork.read(() -> dsl.select(PLAYER_IPS.PLAYER_ID, PLAYER_IPS.IP)
            .from(PLAYER_IPS)
            .where(PLAYER_IPS.RETAINED_UNTIL_AT.isNotNull())
            .limit(limit)
            .fetch(row -> DSL.row(row.value1(), row.value2())));
        if (!held.isEmpty()) {
            unitOfWork.write(() -> dsl.update(PLAYER_IPS)
                .setNull(PLAYER_IPS.RETAINED_UNTIL_AT)
                .where(DSL.row(PLAYER_IPS.PLAYER_ID, PLAYER_IPS.IP).in(held))
                .and(PLAYER_IPS.RETAINED_UNTIL_AT.isNotNull())
                .execute());
        }
        return held.size();
    }
}
