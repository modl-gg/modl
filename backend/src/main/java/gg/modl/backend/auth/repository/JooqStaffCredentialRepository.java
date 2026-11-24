package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.STAFF_CREDENTIALS;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.jooq.tables.records.StaffCredentialsRecord;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqStaffCredentialRepository implements StaffCredentialRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;
    private final Upserts upserts;

    @Override
    public Optional<String> findPasswordHash(String email) {
        return dsl.select(STAFF_CREDENTIALS.PASSWORD_HASH)
            .from(STAFF_CREDENTIALS)
            .where(emailIs(email))
            .fetchOptional(STAFF_CREDENTIALS.PASSWORD_HASH);
    }

    @Override
    public boolean insertIfAbsent(String email, String passwordHash, Instant now) {
        StaffCredentialsRecord row = row(email, passwordHash, now);
        return unitOfWork.write(() -> insertIfAbsent.onPrimaryKey(STAFF_CREDENTIALS).insert(row));
    }

    @Override
    public boolean updateIfMatches(String email, String expectedHash, String passwordHash, Instant now) {
        String stored = TextLimits.require(STAFF_CREDENTIALS.PASSWORD_HASH, passwordHash);
        return unitOfWork.write(() -> dsl.update(STAFF_CREDENTIALS)
            .set(STAFF_CREDENTIALS.PASSWORD_HASH, stored)
            .set(STAFF_CREDENTIALS.UPDATED_AT, now)
            .where(emailIs(email))
            .and(ColumnLookups.matches(STAFF_CREDENTIALS.PASSWORD_HASH, expectedHash))
            .execute() == 1);
    }

    @Override
    public void replace(String email, String passwordHash, Instant now) {
        StaffCredentialsRecord row = row(email, passwordHash, now);
        unitOfWork.write(() -> upserts.onPrimaryKey(STAFF_CREDENTIALS, row, Map.of(
            STAFF_CREDENTIALS.PASSWORD_HASH, row.getPasswordHash(),
            STAFF_CREDENTIALS.UPDATED_AT, now)));
    }

    @Override
    public void delete(String email) {
        unitOfWork.write(() -> dsl.deleteFrom(STAFF_CREDENTIALS).where(emailIs(email)).execute());
    }

    @Override
    public void move(String fromEmail, String toEmail, Instant now) {
        String target = AuthColumns.identity(STAFF_CREDENTIALS.EMAIL, toEmail);
        unitOfWork.write(() -> {
            dsl.deleteFrom(STAFF_CREDENTIALS).where(emailIs(target)).execute();
            dsl.update(STAFF_CREDENTIALS)
                .set(STAFF_CREDENTIALS.EMAIL, target)
                .set(STAFF_CREDENTIALS.UPDATED_AT, now)
                .where(emailIs(fromEmail))
                .execute();
        });
    }

    @Override
    public Set<String> findEmails() {
        return Set.copyOf(dsl.select(STAFF_CREDENTIALS.EMAIL).from(STAFF_CREDENTIALS).fetch(STAFF_CREDENTIALS.EMAIL));
    }

    private static StaffCredentialsRecord row(String email, String passwordHash, Instant now) {
        return new StaffCredentialsRecord(
            AuthColumns.identity(STAFF_CREDENTIALS.EMAIL, email),
            TextLimits.require(STAFF_CREDENTIALS.PASSWORD_HASH, passwordHash),
            now,
            now);
    }

    private static Condition emailIs(String email) {
        return AuthColumns.matchesIdentity(STAFF_CREDENTIALS.EMAIL, email);
    }
}
