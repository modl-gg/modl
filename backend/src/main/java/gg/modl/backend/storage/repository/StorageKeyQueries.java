package gg.modl.backend.storage.repository;

import gg.modl.backend.database.PrefixRange;
import gg.modl.backend.database.TextLimits;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.TableField;
import org.jooq.impl.DSL;

final class StorageKeyQueries {
    private StorageKeyQueries() {
    }

    static boolean canBeStored(TableField<?, String> keyField, String key) {
        return TextLimits.canMatch(keyField, key) && !key.endsWith(" ");
    }

    static List<String> storableKeys(TableField<?, String> keyField, Collection<String> keys) {
        return keys.stream()
            .filter(key -> canBeStored(keyField, key))
            .map(key -> TextLimits.require(keyField, key))
            .distinct()
            .sorted()
            .toList();
    }

    static Condition startsWithAny(TableField<?, String> keyField, Collection<String> prefixes) {
        return DSL.or(prefixes.stream().map(prefix -> PrefixRange.of(keyField, prefix)).toList());
    }

    static PrefixUsage usage(DSLContext dsl, TableField<?, Long> bytes, Condition condition) {
        Record2<Integer, BigDecimal> totals = dsl.select(DSL.count(), DSL.coalesce(DSL.sum(bytes), BigDecimal.ZERO))
            .from(bytes.getTable())
            .where(condition)
            .fetchSingle();
        return new PrefixUsage(totals.value1(), totals.value2().longValue());
    }

    static long sum(DSLContext dsl, TableField<?, Long> bytes, Condition condition) {
        return dsl.select(DSL.coalesce(DSL.sum(bytes), BigDecimal.ZERO))
            .from(bytes.getTable())
            .where(condition)
            .fetchSingle()
            .value1()
            .longValue();
    }
}
