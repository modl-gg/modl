package gg.modl.backend.database;

import java.time.Instant;
import org.jooq.impl.AbstractConverter;
import org.jspecify.annotations.Nullable;

public final class InstantMillisConverter extends AbstractConverter<Long, Instant> {
    public InstantMillisConverter() {
        super(Long.class, Instant.class);
    }

    @Override
    public @Nullable Instant from(@Nullable Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }

    @Override
    public @Nullable Long to(@Nullable Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
