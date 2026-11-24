package gg.modl.backend.ticket.repository;

import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_VERIFICATIONS;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.TicketVerificationsRecord;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeConditions;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqTicketVerificationRepository implements TicketVerificationRepository {
    private static final OneTimeCodeConditions.Columns CODE_COLUMNS = new OneTimeCodeConditions.Columns(
        TICKET_VERIFICATIONS.CODE_HASH, TICKET_VERIFICATIONS.EXPIRES_AT, TICKET_VERIFICATIONS.FAILED_ATTEMPTS);

    private final DSLContext dsl;
    private final ChildRows childRows;
    private final UnitOfWork unitOfWork;

    @Override
    public void lockTicket(String ticketId) {
        childRows.lockRow(TICKETS, TICKETS.ID, ticketId);
    }

    @Override
    public Optional<VerificationFailures> findFailures(String ticketId, Instant now) {
        return dsl.selectFrom(TICKET_VERIFICATIONS)
            .where(failuresOf(ticketId).and(TICKET_VERIFICATIONS.EXPIRES_AT.gt(now)))
            .orderBy(TICKET_VERIFICATIONS.ID)
            .limit(1)
            .fetchOptional()
            .map(row -> new VerificationFailures(row.getFailedAttempts(), row.getExpiresAt()));
    }

    @Override
    public void countFailure(String ticketId, Instant now, Instant newWindowEnd) {
        unitOfWork.write(() -> {
            Condition failures = failuresOf(ticketId);
            int counted = dsl.update(TICKET_VERIFICATIONS)
                .set(TICKET_VERIFICATIONS.FAILED_ATTEMPTS, TICKET_VERIFICATIONS.FAILED_ATTEMPTS.plus(1))
                .where(failures.and(TICKET_VERIFICATIONS.EXPIRES_AT.gt(now)))
                .execute();
            if (counted > 0) {
                return;
            }
            dsl.deleteFrom(TICKET_VERIFICATIONS).where(failures).execute();
            dsl.insertInto(TICKET_VERIFICATIONS)
                .set(row(new TicketVerificationRecord(EntityIds.next(), ticketId, null, null, null, 1, newWindowEnd)))
                .execute();
        });
    }

    @Override
    public Optional<Instant> findCodeExpiry(String ticketId) {
        return dsl.select(TICKET_VERIFICATIONS.EXPIRES_AT)
            .from(TICKET_VERIFICATIONS)
            .where(codeOf(ticketId))
            .orderBy(TICKET_VERIFICATIONS.EXPIRES_AT.desc(), TICKET_VERIFICATIONS.ID)
            .limit(1)
            .fetchOptional(TICKET_VERIFICATIONS.EXPIRES_AT);
    }

    @Override
    public void replaceCode(TicketVerificationRecord verification) {
        TicketVerificationsRecord row = row(verification);
        unitOfWork.write(() -> {
            childRows.lockRow(TICKETS, TICKETS.ID, row.getTicketId());
            dsl.deleteFrom(TICKET_VERIFICATIONS).where(codeOf(row.getTicketId())).execute();
            dsl.insertInto(TICKET_VERIFICATIONS).set(row).execute();
        });
    }

    @Override
    public void insertToken(TicketVerificationRecord verification) {
        TicketVerificationsRecord row = row(verification);
        unitOfWork.write(() -> {
            dsl.insertInto(TICKET_VERIFICATIONS).set(row).execute();
        });
    }

    @Override
    public Optional<TicketVerificationRecord> consumeMatchingCode(String ticketId, String codeHash, Instant now) {
        if (!TextLimits.canMatch(TICKET_VERIFICATIONS.CODE_HASH, codeHash)) {
            return Optional.empty();
        }
        Condition matching = OneTimeCodeConditions.activeUnlockedCode(codeOf(ticketId), CODE_COLUMNS,
            TextLimits.require(TICKET_VERIFICATIONS.CODE_HASH, codeHash), now);
        return unitOfWork.write(() -> dsl.selectFrom(TICKET_VERIFICATIONS)
            .where(matching)
            .orderBy(TICKET_VERIFICATIONS.ID)
            .limit(1)
            .fetchOptional()
            .filter(row -> dsl.deleteFrom(TICKET_VERIFICATIONS)
                .where(TICKET_VERIFICATIONS.ID.eq(row.getId()).and(matching))
                .execute() == 1)
            .map(JooqTicketVerificationRepository::verification));
    }

    @Override
    public boolean incrementFailedAttempts(String ticketId, Instant now) {
        Condition active = OneTimeCodeConditions.active(codeOf(ticketId), CODE_COLUMNS, now)
            .and(TICKET_VERIFICATIONS.FAILED_ATTEMPTS.lt(OneTimeCodeConditions.MAX_FAILED_ATTEMPTS));
        return unitOfWork.write(() -> dsl.update(TICKET_VERIFICATIONS)
            .set(TICKET_VERIFICATIONS.FAILED_ATTEMPTS, OneTimeCodeConditions.incrementedFailedAttempts(CODE_COLUMNS))
            .where(active)
            .execute() > 0);
    }

    @Override
    public boolean existsActiveToken(String ticketId, String tokenHash, Instant now) {
        return dsl.fetchExists(dsl.selectFrom(TICKET_VERIFICATIONS)
            .where(ColumnLookups.matches(TICKET_VERIFICATIONS.TICKET_ID, ticketId))
            .and(ColumnLookups.matches(TICKET_VERIFICATIONS.TOKEN_HASH, tokenHash))
            .and(TICKET_VERIFICATIONS.EXPIRES_AT.ge(now)));
    }

    private static Condition codeOf(String ticketId) {
        return ofTicketWithoutToken(ticketId).and(TICKET_VERIFICATIONS.CODE_HASH.isNotNull());
    }

    private static Condition failuresOf(String ticketId) {
        return ofTicketWithoutToken(ticketId).and(TICKET_VERIFICATIONS.CODE_HASH.isNull());
    }

    private static Condition ofTicketWithoutToken(String ticketId) {
        return ColumnLookups.matches(TICKET_VERIFICATIONS.TICKET_ID, ticketId).and(TICKET_VERIFICATIONS.TOKEN_HASH.isNull());
    }

    private static TicketVerificationsRecord row(TicketVerificationRecord verification) {
        TicketVerificationsRecord row = new TicketVerificationsRecord();
        row.setId(TextLimits.require(TICKET_VERIFICATIONS.ID, verification.id()));
        row.setTicketId(TextLimits.require(TICKET_VERIFICATIONS.TICKET_ID, verification.ticketId()));
        row.setTokenHash(TextLimits.requireIfPresent(TICKET_VERIFICATIONS.TOKEN_HASH, verification.tokenHash()));
        row.setCodeHash(TextLimits.requireIfPresent(TICKET_VERIFICATIONS.CODE_HASH, verification.codeHash()));
        row.setEmail(TextLimits.requireIfPresent(TICKET_VERIFICATIONS.EMAIL, verification.email()));
        row.setFailedAttempts(verification.failedAttempts());
        row.setExpiresAt(verification.expiresAt());
        return row;
    }

    private static TicketVerificationRecord verification(TicketVerificationsRecord row) {
        return new TicketVerificationRecord(row.getId(), row.getTicketId(), row.getTokenHash(), row.getCodeHash(),
            row.getEmail(), row.getFailedAttempts(), row.getExpiresAt());
    }
}
