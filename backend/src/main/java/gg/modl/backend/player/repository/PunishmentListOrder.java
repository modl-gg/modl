package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;

import java.util.List;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;

final class PunishmentListOrder {
    static final Name KEY = DSL.name("list_key");

    private static final long NEVER_STARTED = 0L;
    private static final long PERMANENT = Long.MAX_VALUE;

    private PunishmentListOrder() {
    }

    static Field<Long> keyOf(PunishmentListQuery.SortKey sortKey) {
        return switch (sortKey) {
            case ISSUED -> PUNISHMENTS.ISSUED_AT.coerce(Long.class);
            case STARTED -> DSL.coalesce(PUNISHMENTS.STARTED_AT.coerce(Long.class), DSL.inline(NEVER_STARTED));
            case DURATION -> DSL.when(PUNISHMENTS.DURATION_MS.gt(DSL.inline(0L)), PUNISHMENTS.DURATION_MS)
                .otherwise(DSL.inline(PERMANENT));
        };
    }

    static Condition after(Field<Long> key, boolean ascending, @Nullable PunishmentListCursor cursor) {
        if (cursor == null) {
            return DSL.noCondition();
        }
        return ascending
            ? DSL.row(key, PUNISHMENTS.ID).gt(cursor.key(), cursor.id())
            : DSL.row(key, PUNISHMENTS.ID).lt(cursor.key(), cursor.id());
    }

    static List<SortField<?>> orderBy(Field<Long> key, boolean ascending) {
        return orderBy(key, PUNISHMENTS.ID, ascending);
    }

    static List<SortField<?>> orderBy(Field<Long> key, Field<String> id, boolean ascending) {
        return ascending
            ? List.of(key.asc(), id.asc())
            : List.of(key.desc(), id.desc());
    }
}
