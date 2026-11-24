package gg.modl.backend.settings.repository;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;

import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.jooq.tables.records.SettingsRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqSettingsRepository implements SettingsRepository {
    private static final long FIRST_STORED_VERSION = 1;

    private final DSLContext dsl;
    private final InsertIfAbsent insertIfAbsent;
    private final Upserts upserts;
    private final Clock clock;

    @Override
    public Optional<SettingsRow> find(String type) {
        return dsl.selectFrom(SETTINGS)
            .where(SETTINGS.TYPE.eq(boundType(type)))
            .fetchOptional()
            .map(row -> new SettingsRow(row.getType(), row.getDataJson(), row.getVersion(), row.getUpdatedAt()));
    }

    @Override
    public boolean insertIfAbsent(String type, String json) {
        return insertIfAbsent.onPrimaryKey(SETTINGS).insert(firstVersion(type, json, clock.instant()));
    }

    @Override
    public boolean updateIfVersion(String type, long expectedVersion, String json) {
        return dsl.update(SETTINGS)
            .set(SETTINGS.DATA_JSON, json)
            .set(SETTINGS.VERSION, SETTINGS.VERSION.plus(1))
            .set(SETTINGS.UPDATED_AT, clock.instant())
            .where(SETTINGS.TYPE.eq(boundType(type)).and(SETTINGS.VERSION.eq(expectedVersion)))
            .execute() == 1;
    }

    @Override
    public void upsert(String type, String json) {
        Instant now = clock.instant();
        Map<Field<?>, Object> assignments = Map.of(
            SETTINGS.DATA_JSON, json,
            SETTINGS.VERSION, SETTINGS.VERSION.plus(1),
            SETTINGS.UPDATED_AT, now);
        upserts.onPrimaryKey(SETTINGS, firstVersion(type, json, now), assignments);
    }

    @Override
    public boolean delete(String type) {
        return dsl.deleteFrom(SETTINGS)
            .where(SETTINGS.TYPE.eq(boundType(type)))
            .execute() == 1;
    }

    private static SettingsRecord firstVersion(String type, String json, Instant now) {
        return new SettingsRecord(boundType(type), json, FIRST_STORED_VERSION, now);
    }

    private static String boundType(String type) {
        return TextLimits.require(SETTINGS.TYPE, type);
    }
}
