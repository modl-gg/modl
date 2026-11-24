package gg.modl.backend.analytics.repository;

import gg.modl.backend.database.TimeBuckets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Table;
import org.jooq.impl.DSL;

public final class GroupedCounts {
    private static final Name BUCKET = DSL.name("bucket_start");

    private GroupedCounts() {
    }

    public static <K> Map<K, Long> byKey(DSLContext dsl, Table<?> table, Field<K> key, Condition condition) {
        Field<Integer> count = DSL.count();
        Map<K, Long> counts = new LinkedHashMap<>();
        dsl.select(key, count)
            .from(table)
            .where(condition)
            .groupBy(key)
            .orderBy(count.desc(), key.asc())
            .fetch()
            .forEach(row -> counts.put(row.value1(), row.value2().longValue()));
        return counts;
    }

    public static List<TimeBucketCount> byDay(DSLContext dsl, Table<?> table, Field<Instant> at, Condition condition) {
        return byBucket(dsl, table, TimeBuckets.dayStart(at), condition);
    }

    public static List<TimeBucketCount> byHour(DSLContext dsl, Table<?> table, Field<Instant> at, Condition condition) {
        return byBucket(dsl, table, TimeBuckets.hourStart(at), condition);
    }

    private static List<TimeBucketCount> byBucket(DSLContext dsl, Table<?> table, Field<Long> start, Condition condition) {
        Field<Long> bucket = DSL.field(BUCKET, Long.class);
        return dsl.select(start.as(BUCKET), DSL.count())
            .from(table)
            .where(condition)
            .groupBy(bucket)
            .orderBy(bucket)
            .fetch(row -> new TimeBucketCount(Instant.ofEpochMilli(row.value1()), row.value2()));
    }
}
