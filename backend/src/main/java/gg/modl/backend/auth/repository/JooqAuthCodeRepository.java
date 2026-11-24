package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;

import gg.modl.backend.auth.data.AuthCode;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitFrame;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.AuthCodesRecord;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeConditions;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqAuthCodeRepository implements AuthCodeRepository {
    private static final OneTimeCodeConditions.Columns COLUMNS =
        new OneTimeCodeConditions.Columns(AUTH_CODES.CODE_HASH, AUTH_CODES.CODE_EXPIRES_AT, AUTH_CODES.FAILED_ATTEMPTS);

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;
    private final DatabaseDialect dialect;
    private final Clock clock;

    @Override
    public Optional<AuthCode> find(String email) {
        return dsl.selectFrom(AUTH_CODES)
            .where(identityIs(email))
            .and(AUTH_CODES.EXPIRES_AT.gt(clock.instant()))
            .fetchOptional()
            .map(JooqAuthCodeRepository::toAuthCode);
    }

    @Override
    public void lock(String email) {
        UnitFrame.requireWrite();
        if (dialect.isExternal()) {
            dsl.selectOne().from(AUTH_CODES).where(identityIs(email)).forUpdate().fetch();
        }
    }

    @Override
    public void delete(String email) {
        unitOfWork.write(() -> dsl.deleteFrom(AUTH_CODES).where(identityIs(email)).execute());
    }

    @Override
    public boolean issue(String email, String codeHash, Instant issuedAt, Instant codeExpiresAt, Instant previousIssuedBefore) {
        String identity = AuthColumns.identity(AUTH_CODES.EMAIL, email);
        String storedHash = TextLimits.require(AUTH_CODES.CODE_HASH, codeHash);
        return unitOfWork.write(() -> replaceOutsideCooldown(identity, storedHash, issuedAt, codeExpiresAt, previousIssuedBefore)
            || insertIfAbsent.onPrimaryKey(AUTH_CODES)
                .insert(new AuthCodesRecord(identity, storedHash, 0, issuedAt, codeExpiresAt, 0, null, codeExpiresAt)));
    }

    @Override
    public Optional<AuthCode> consumeIfHashMatches(String email, String codeHash, Instant now, Instant failureWindowStartedAfter,
                                                   int maxRecentFailures) {
        Condition usable = OneTimeCodeConditions.activeUnlockedCode(identityIs(email), COLUMNS, codeHash, now)
            .and(outsideFailureWindow(failureWindowStartedAfter).or(AUTH_CODES.RECENT_FAILURES.lt(maxRecentFailures)));
        return unitOfWork.write(() -> SingleUseRows.consume(dsl, AUTH_CODES, AUTH_CODES.EMAIL, usable))
            .map(JooqAuthCodeRepository::toAuthCode);
    }

    @Override
    public void recordFailedAttempt(String email, Instant now, Instant failureWindowStartedAfter, Instant newWindowRetainedUntil) {
        Condition active = OneTimeCodeConditions.active(identityIs(email), COLUMNS, now);
        unitOfWork.write(() -> {
            if (incrementWithinWindow(active, failureWindowStartedAfter)) {
                return;
            }
            if (!startFailureWindow(active, now, failureWindowStartedAfter, newWindowRetainedUntil)) {
                incrementWithinWindow(active, failureWindowStartedAfter);
            }
        });
    }

    private boolean replaceOutsideCooldown(String identity, String codeHash, Instant issuedAt, Instant codeExpiresAt,
                                           Instant previousIssuedBefore) {
        return dsl.update(AUTH_CODES)
            .set(AUTH_CODES.CODE_HASH, codeHash)
            .set(AUTH_CODES.ISSUED_AT, issuedAt)
            .set(AUTH_CODES.CODE_EXPIRES_AT, codeExpiresAt)
            .set(AUTH_CODES.FAILED_ATTEMPTS, 0)
            .set(AUTH_CODES.EXPIRES_AT, retainedUntilAtLeast(codeExpiresAt))
            .where(AUTH_CODES.EMAIL.eq(identity))
            .and(AUTH_CODES.ISSUED_AT.isNull().or(AUTH_CODES.ISSUED_AT.le(previousIssuedBefore)))
            .execute() == 1;
    }

    private boolean incrementWithinWindow(Condition active, Instant failureWindowStartedAfter) {
        return dsl.update(AUTH_CODES)
            .set(AUTH_CODES.FAILED_ATTEMPTS, OneTimeCodeConditions.incrementedFailedAttempts(COLUMNS))
            .set(AUTH_CODES.RECENT_FAILURES, AUTH_CODES.RECENT_FAILURES.plus(1))
            .where(active)
            .and(AUTH_CODES.FAILURE_WINDOW_STARTED_AT.gt(failureWindowStartedAfter))
            .execute() == 1;
    }

    private boolean startFailureWindow(Condition active, Instant now, Instant failureWindowStartedAfter, Instant retainedUntil) {
        return dsl.update(AUTH_CODES)
            .set(AUTH_CODES.FAILED_ATTEMPTS, OneTimeCodeConditions.incrementedFailedAttempts(COLUMNS))
            .set(AUTH_CODES.RECENT_FAILURES, 1)
            .set(AUTH_CODES.FAILURE_WINDOW_STARTED_AT, now)
            .set(AUTH_CODES.EXPIRES_AT, retainedUntilAtLeast(retainedUntil))
            .where(active)
            .and(outsideFailureWindow(failureWindowStartedAfter))
            .execute() == 1;
    }

    private static Field<Instant> retainedUntilAtLeast(Instant instant) {
        Field<Instant> minimum = DSL.val(instant, AUTH_CODES.EXPIRES_AT);
        return DSL.when(AUTH_CODES.EXPIRES_AT.lt(instant), minimum).otherwise(AUTH_CODES.EXPIRES_AT);
    }

    private static Condition outsideFailureWindow(Instant failureWindowStartedAfter) {
        return AUTH_CODES.FAILURE_WINDOW_STARTED_AT.isNull()
            .or(AUTH_CODES.FAILURE_WINDOW_STARTED_AT.le(failureWindowStartedAfter));
    }

    private static Condition identityIs(String email) {
        return AuthColumns.matchesIdentity(AUTH_CODES.EMAIL, email);
    }

    private static AuthCode toAuthCode(AuthCodesRecord row) {
        return AuthCode.builder()
            .email(row.getEmail())
            .codeHash(row.getCodeHash())
            .failedAttempts(row.getFailedAttempts())
            .issuedAt(row.getIssuedAt())
            .codeExpiresAt(row.getCodeExpiresAt())
            .recentFailures(row.getRecentFailures())
            .failureWindowStartedAt(row.getFailureWindowStartedAt())
            .expiresAt(row.getExpiresAt())
            .build();
    }
}
