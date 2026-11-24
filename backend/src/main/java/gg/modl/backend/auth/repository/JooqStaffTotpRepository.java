package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.STAFF_RECOVERY_CODES;
import static gg.modl.backend.database.jooq.Tables.STAFF_TOTP;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.jooq.tables.records.StaffRecoveryCodesRecord;
import gg.modl.backend.database.jooq.tables.records.StaffTotpRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqStaffTotpRepository implements StaffTotpRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final Upserts upserts;

    @Override
    public Optional<StaffTotp> find(String email) {
        return dsl.selectFrom(STAFF_TOTP).where(totpEmailIs(email)).fetchOptional().map(JooqStaffTotpRepository::toTotp);
    }

    @Override
    public boolean isEnabled(String email) {
        return dsl.fetchExists(STAFF_TOTP, totpEmailIs(email).and(STAFF_TOTP.TOTP_SECRET.isNotNull()));
    }

    @Override
    public void savePending(String email, String pendingSecret, Instant expiresAt, Instant now) {
        StaffTotpRecord row = new StaffTotpRecord();
        row.setEmail(AuthColumns.identity(STAFF_TOTP.EMAIL, email));
        row.setPendingTotpSecret(TextLimits.require(STAFF_TOTP.PENDING_TOTP_SECRET, pendingSecret));
        row.setPendingExpiresAt(expiresAt);
        row.setUpdatedAt(now);
        Map<Field<?>, Object> assignments = new HashMap<>();
        assignments.put(STAFF_TOTP.PENDING_TOTP_SECRET, row.getPendingTotpSecret());
        assignments.put(STAFF_TOTP.PENDING_EXPIRES_AT, expiresAt);
        assignments.put(STAFF_TOTP.UPDATED_AT, now);
        unitOfWork.write(() -> upserts.onPrimaryKey(STAFF_TOTP, row, assignments));
    }

    @Override
    public boolean enablePending(String email, String pendingSecret, long step, Instant now) {
        return unitOfWork.write(() -> dsl.update(STAFF_TOTP)
            .set(STAFF_TOTP.TOTP_SECRET, STAFF_TOTP.PENDING_TOTP_SECRET)
            .set(STAFF_TOTP.LAST_USED_STEP, step)
            .set(STAFF_TOTP.ENABLED_AT, now)
            .setNull(STAFF_TOTP.PENDING_TOTP_SECRET)
            .setNull(STAFF_TOTP.PENDING_EXPIRES_AT)
            .set(STAFF_TOTP.UPDATED_AT, now)
            .where(totpEmailIs(email))
            .and(ColumnLookups.matches(STAFF_TOTP.PENDING_TOTP_SECRET, pendingSecret))
            .and(STAFF_TOTP.PENDING_EXPIRES_AT.gt(now))
            .execute() == 1);
    }

    @Override
    public boolean advanceStep(String email, String secret, long step) {
        return unitOfWork.write(() -> dsl.update(STAFF_TOTP)
            .set(STAFF_TOTP.LAST_USED_STEP, step)
            .where(totpEmailIs(email))
            .and(ColumnLookups.matches(STAFF_TOTP.TOTP_SECRET, secret))
            .and(STAFF_TOTP.LAST_USED_STEP.isNull().or(STAFF_TOTP.LAST_USED_STEP.lt(step)))
            .execute() == 1);
    }

    @Override
    public void delete(String email) {
        unitOfWork.write(() -> {
            dsl.deleteFrom(STAFF_RECOVERY_CODES).where(codeEmailIs(email)).execute();
            dsl.deleteFrom(STAFF_TOTP).where(totpEmailIs(email)).execute();
        });
    }

    @Override
    public void move(String fromEmail, String toEmail) {
        String target = AuthColumns.identity(STAFF_TOTP.EMAIL, toEmail);
        unitOfWork.write(() -> {
            delete(target);
            dsl.update(STAFF_TOTP).set(STAFF_TOTP.EMAIL, target).where(totpEmailIs(fromEmail)).execute();
            dsl.update(STAFF_RECOVERY_CODES).set(STAFF_RECOVERY_CODES.EMAIL, target).where(codeEmailIs(fromEmail)).execute();
        });
    }

    @Override
    public void replaceRecoveryCodes(String email, Collection<String> codeHashes, Instant now) {
        String identity = AuthColumns.identity(STAFF_RECOVERY_CODES.EMAIL, email);
        unitOfWork.write(() -> {
            dsl.deleteFrom(STAFF_RECOVERY_CODES).where(codeEmailIs(identity)).execute();
            codeHashes.stream()
                .map(hash -> new StaffRecoveryCodesRecord(identity, TextLimits.require(STAFF_RECOVERY_CODES.CODE_HASH, hash), now))
                .forEach(row -> dsl.insertInto(STAFF_RECOVERY_CODES).set(row).execute());
        });
    }

    @Override
    public boolean consumeRecoveryCode(String email, String codeHash) {
        return unitOfWork.write(() -> dsl.deleteFrom(STAFF_RECOVERY_CODES)
            .where(codeEmailIs(email))
            .and(ColumnLookups.matches(STAFF_RECOVERY_CODES.CODE_HASH, codeHash))
            .execute() == 1);
    }

    @Override
    public int countRecoveryCodes(String email) {
        return dsl.fetchCount(STAFF_RECOVERY_CODES, codeEmailIs(email));
    }

    @Override
    public Set<String> findEnabledEmails() {
        return Set.copyOf(dsl.select(STAFF_TOTP.EMAIL)
            .from(STAFF_TOTP)
            .where(STAFF_TOTP.TOTP_SECRET.isNotNull())
            .fetch(STAFF_TOTP.EMAIL));
    }

    private static Condition totpEmailIs(String email) {
        return AuthColumns.matchesIdentity(STAFF_TOTP.EMAIL, email);
    }

    private static Condition codeEmailIs(String email) {
        return AuthColumns.matchesIdentity(STAFF_RECOVERY_CODES.EMAIL, email);
    }

    private static StaffTotp toTotp(StaffTotpRecord row) {
        return new StaffTotp(row.getEmail(), row.getTotpSecret(), row.getLastUsedStep(), row.getEnabledAt(),
            row.getPendingTotpSecret(), row.getPendingExpiresAt());
    }
}
