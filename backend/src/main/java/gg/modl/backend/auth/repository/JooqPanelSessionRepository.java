package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;

import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.PanelSessionsRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqPanelSessionRepository implements PanelSessionRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    @Override
    public void insert(PanelSession session) {
        PanelSessionsRecord row = new PanelSessionsRecord(
            TextLimits.require(PANEL_SESSIONS.ID_HASH, session.publicId()),
            AuthColumns.identity(PANEL_SESSIONS.EMAIL, session.email()),
            TextLimits.require(PANEL_SESSIONS.SESSION_KEY_HASH, session.sessionKeyHash()),
            TextLimits.requireIfPresent(PANEL_SESSIONS.IP_ADDRESS, session.ipAddress()),
            session.userAgent(),
            session.createdAt(),
            session.expiresAt(),
            TextLimits.require(PANEL_SESSIONS.AUTH_METHOD, session.authMethod().name()),
            session.secondFactorVerified());
        unitOfWork.write(() -> dsl.insertInto(PANEL_SESSIONS).set(row).execute());
    }

    @Override
    public Optional<PanelSession> findByPublicId(String publicId) {
        return dsl.selectFrom(PANEL_SESSIONS)
            .where(publicIdIs(publicId))
            .and(activeAt(clock.instant()))
            .fetchOptional()
            .map(JooqPanelSessionRepository::toSession);
    }

    @Override
    public List<PanelSession> findByEmail(String email, Instant now) {
        return dsl.selectFrom(PANEL_SESSIONS)
            .where(AuthColumns.matchesIdentity(PANEL_SESSIONS.EMAIL, email))
            .and(activeAt(now))
            .orderBy(PANEL_SESSIONS.CREATED_AT, PANEL_SESSIONS.ID_HASH)
            .fetch()
            .map(JooqPanelSessionRepository::toSession);
    }

    @Override
    public Set<String> findActivePublicIds(Collection<String> publicIds, Instant now, boolean secondFactorVerifiedOnly) {
        Condition admitted = secondFactorVerifiedOnly ? PANEL_SESSIONS.SECOND_FACTOR_VERIFIED.isTrue() : DSL.trueCondition();
        List<String> active = InClauseChunks.collect(storedPublicIds(publicIds), chunk -> dsl.select(PANEL_SESSIONS.ID_HASH)
            .from(PANEL_SESSIONS)
            .where(PANEL_SESSIONS.ID_HASH.in(chunk))
            .and(activeAt(now))
            .and(admitted)
            .fetch(PANEL_SESSIONS.ID_HASH));
        return Set.copyOf(active);
    }

    @Override
    public boolean refresh(String publicId, Instant expiresAt) {
        return unitOfWork.write(() -> dsl.update(PANEL_SESSIONS)
            .set(PANEL_SESSIONS.EXPIRES_AT, expiresAt)
            .where(publicIdIs(publicId))
            .and(activeAt(clock.instant()))
            .execute() == 1);
    }

    @Override
    public boolean markSecondFactorVerified(String publicId) {
        return unitOfWork.write(() -> dsl.update(PANEL_SESSIONS)
            .set(PANEL_SESSIONS.SECOND_FACTOR_VERIFIED, true)
            .where(publicIdIs(publicId))
            .and(activeAt(clock.instant()))
            .execute() == 1);
    }

    @Override
    public int clearSecondFactorVerified(String email) {
        return unitOfWork.write(() -> dsl.update(PANEL_SESSIONS)
            .set(PANEL_SESSIONS.SECOND_FACTOR_VERIFIED, false)
            .where(AuthColumns.matchesIdentity(PANEL_SESSIONS.EMAIL, email))
            .and(PANEL_SESSIONS.SECOND_FACTOR_VERIFIED.isTrue())
            .execute());
    }

    @Override
    public boolean deleteByPublicId(String publicId) {
        return unitOfWork.write(() -> dsl.deleteFrom(PANEL_SESSIONS).where(publicIdIs(publicId)).execute() == 1);
    }

    @Override
    public int deleteByPublicIds(Collection<String> publicIds) {
        List<String> stored = storedPublicIds(publicIds);
        return unitOfWork.write(() -> InClauseChunks.sum(stored,
            chunk -> dsl.deleteFrom(PANEL_SESSIONS).where(PANEL_SESSIONS.ID_HASH.in(chunk)).execute()));
    }

    @Override
    public int deleteByEmail(String email) {
        return unitOfWork.write(() -> dsl.deleteFrom(PANEL_SESSIONS)
            .where(AuthColumns.matchesIdentity(PANEL_SESSIONS.EMAIL, email))
            .execute());
    }

    @Override
    public int deleteByEmailExcept(String email, String keptPublicId) {
        return unitOfWork.write(() -> dsl.deleteFrom(PANEL_SESSIONS)
            .where(AuthColumns.matchesIdentity(PANEL_SESSIONS.EMAIL, email))
            .and(ColumnLookups.differsFrom(PANEL_SESSIONS.ID_HASH, keptPublicId))
            .execute());
    }

    private static List<String> storedPublicIds(Collection<String> publicIds) {
        return publicIds.stream()
            .map(publicId -> TextLimits.require(PANEL_SESSIONS.ID_HASH, publicId))
            .sorted()
            .toList();
    }

    private static Condition publicIdIs(String publicId) {
        return ColumnLookups.matches(PANEL_SESSIONS.ID_HASH, publicId);
    }

    private static Condition activeAt(Instant now) {
        return PANEL_SESSIONS.EXPIRES_AT.gt(now);
    }

    private static PanelSession toSession(PanelSessionsRecord row) {
        return new PanelSession(row.getIdHash(), row.getEmail(), row.getSessionKeyHash(), row.getIpAddress(), row.getUserAgent(),
            row.getCreatedAt(), row.getExpiresAt(), AuthMethod.fromStored(row.getAuthMethod()), row.getSecondFactorVerified());
    }
}
