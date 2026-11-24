package gg.modl.backend.infrastructure.onetimecode;

import java.time.Instant;
import org.jooq.Condition;
import org.jooq.Field;

public final class OneTimeCodeConditions {
    public static final int MAX_FAILED_ATTEMPTS = 5;

    private OneTimeCodeConditions() {
    }

    public record Columns(Field<String> codeHash, Field<Instant> expiresAt, Field<Integer> failedAttempts) {
    }

    public static Condition activeUnlockedCode(Condition identity, Columns columns, String codeHash, Instant now) {
        return activeUnlockedCode(identity, columns, codeHash, now, MAX_FAILED_ATTEMPTS);
    }

    public static Condition activeUnlockedCode(Condition identity, Columns columns, String codeHash, Instant now,
                                               int maxFailedAttempts) {
        return active(identity, columns, now)
            .and(columns.codeHash().eq(codeHash))
            .and(columns.failedAttempts().lt(maxFailedAttempts));
    }

    public static Condition active(Condition identity, Columns columns, Instant now) {
        return identity.and(columns.expiresAt().gt(now));
    }

    public static Field<Integer> incrementedFailedAttempts(Columns columns) {
        return columns.failedAttempts().plus(1);
    }
}
