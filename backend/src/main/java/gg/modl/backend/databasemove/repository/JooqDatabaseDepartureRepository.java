package gg.modl.backend.databasemove.repository;

import static gg.modl.backend.database.jooq.Tables.DATABASE_DEPARTURE;
import static gg.modl.backend.database.sql.DepartureMarks.ROW_ID;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.sql.DatabaseDeparture;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqDatabaseDepartureRepository implements DatabaseDepartureRepository {
    private final DSLContext dsl;

    @Override
    public void mark(DatabaseDeparture departure) {
        String token = TextLimits.require(DATABASE_DEPARTURE.MOVE_TOKEN, departure.moveToken());
        String movedTo = TextLimits.require(DATABASE_DEPARTURE.MOVED_TO, departure.movedTo());
        dsl.deleteFrom(DATABASE_DEPARTURE).where(DATABASE_DEPARTURE.ID.eq(ROW_ID)).execute();
        dsl.insertInto(DATABASE_DEPARTURE)
            .set(DATABASE_DEPARTURE.ID, ROW_ID)
            .set(DATABASE_DEPARTURE.MOVE_TOKEN, token)
            .set(DATABASE_DEPARTURE.MOVED_TO, movedTo)
            .set(DATABASE_DEPARTURE.MOVED_AT, departure.movedAt())
            .execute();
    }

    @Override
    public void clear() {
        dsl.deleteFrom(DATABASE_DEPARTURE).where(DATABASE_DEPARTURE.ID.eq(ROW_ID)).execute();
    }
}
