package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.PASSWORD_RESET_TOKENS;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.PasswordResetTokensRecord;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqPasswordResetTokenRepository implements PasswordResetTokenRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    @Override
    public void replace(String email, String tokenHash, Instant now, Instant expiresAt) {
        PasswordResetTokensRecord row = new PasswordResetTokensRecord(
            AuthColumns.identity(PASSWORD_RESET_TOKENS.EMAIL, email),
            TextLimits.require(PASSWORD_RESET_TOKENS.TOKEN_HASH, tokenHash),
            now,
            expiresAt);
        unitOfWork.write(() -> {
            dsl.deleteFrom(PASSWORD_RESET_TOKENS).where(emailIs(email)).execute();
            dsl.insertInto(PASSWORD_RESET_TOKENS).set(row).execute();
        });
    }

    @Override
    public Optional<PasswordResetToken> findActive(String tokenHash, Instant now) {
        return dsl.selectFrom(PASSWORD_RESET_TOKENS)
            .where(usable(tokenHash, now))
            .fetchOptional()
            .map(JooqPasswordResetTokenRepository::toToken);
    }

    @Override
    public Optional<PasswordResetToken> consumeActive(String tokenHash, Instant now) {
        return unitOfWork.write(() -> SingleUseRows.consume(dsl, PASSWORD_RESET_TOKENS, PASSWORD_RESET_TOKENS.EMAIL, usable(tokenHash, now))
            .map(JooqPasswordResetTokenRepository::toToken));
    }

    @Override
    public void deleteByEmail(String email) {
        unitOfWork.write(() -> dsl.deleteFrom(PASSWORD_RESET_TOKENS).where(emailIs(email)).execute());
    }

    private static Condition usable(String tokenHash, Instant now) {
        return ColumnLookups.matches(PASSWORD_RESET_TOKENS.TOKEN_HASH, tokenHash).and(PASSWORD_RESET_TOKENS.EXPIRES_AT.gt(now));
    }

    private static Condition emailIs(String email) {
        return AuthColumns.matchesIdentity(PASSWORD_RESET_TOKENS.EMAIL, email);
    }

    private static PasswordResetToken toToken(PasswordResetTokensRecord row) {
        return new PasswordResetToken(row.getEmail(), row.getExpiresAt());
    }
}
