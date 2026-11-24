package gg.modl.backend.staff.repository;

import static gg.modl.backend.database.jooq.Tables.INVITATIONS;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.InvitationsRecord;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.staff.data.Invitation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqInvitationRepository implements InvitationRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    @Override
    public void insert(Invitation invitation) {
        unitOfWork.write(() -> {
            InvitationsRecord row = toRow(invitation);
            dsl.insertInto(INVITATIONS).set(row).execute();
            invitation.setId(row.getId());
        });
    }

    @Override
    public Optional<Invitation> findById(String id) {
        return dsl.selectFrom(INVITATIONS).where(hasId(id)).fetchOptional().map(JooqInvitationRepository::toInvitation);
    }

    @Override
    public List<Invitation> findActive(Instant now) {
        return dsl.selectFrom(INVITATIONS)
            .where(isActive(now))
            .orderBy(INVITATIONS.CREATED_AT, INVITATIONS.ID)
            .fetch()
            .map(JooqInvitationRepository::toInvitation);
    }

    @Override
    public long countActive(Instant now) {
        return dsl.fetchCount(INVITATIONS, isActive(now));
    }

    @Override
    public boolean existsActiveByEmail(String email, Instant now) {
        Condition hasEmail = ColumnLookups.matches(INVITATIONS.EMAIL, EmailAddressUtil.normalize(email));
        return dsl.fetchExists(INVITATIONS, hasEmail.and(isActive(now)));
    }

    @Override
    public Optional<Invitation> findActiveByTokenHash(String tokenHash, Instant now) {
        return dsl.selectFrom(INVITATIONS)
            .where(hasTokenHash(tokenHash).and(isActive(now)))
            .fetchOptional()
            .map(JooqInvitationRepository::toInvitation);
    }

    @Override
    public Optional<Invitation> consumeActive(String tokenHash, Instant now) {
        return unitOfWork.write(() -> {
            Condition redeemable = hasTokenHash(tokenHash).and(isActive(now));
            return dsl.selectFrom(INVITATIONS)
                .where(redeemable)
                .fetchOptional()
                .filter(row -> dsl.deleteFrom(INVITATIONS)
                    .where(INVITATIONS.ID.eq(row.getId()).and(redeemable))
                    .execute() == 1)
                .map(JooqInvitationRepository::toInvitation);
        });
    }

    @Override
    public boolean deleteById(String id) {
        return unitOfWork.write(() -> dsl.deleteFrom(INVITATIONS).where(hasId(id)).execute() == 1);
    }

    @Override
    public boolean rotateToken(String id, String expectedTokenHash, String tokenHash, Instant newExpiresAt, Instant updatedAt) {
        return unitOfWork.write(() -> dsl.update(INVITATIONS)
            .set(INVITATIONS.TOKEN_HASH, TextLimits.require(INVITATIONS.TOKEN_HASH, tokenHash))
            .set(INVITATIONS.EXPIRES_AT, newExpiresAt)
            .set(INVITATIONS.UPDATED_AT, updatedAt)
            .where(hasId(id).and(hasTokenHash(expectedTokenHash)))
            .execute() == 1);
    }

    private static Condition hasId(String id) {
        return ColumnLookups.matches(INVITATIONS.ID, id);
    }

    private static Condition hasTokenHash(String tokenHash) {
        return ColumnLookups.matches(INVITATIONS.TOKEN_HASH, tokenHash);
    }

    private static Condition isActive(Instant now) {
        return INVITATIONS.EXPIRES_AT.gt(now);
    }

    private static InvitationsRecord toRow(Invitation invitation) {
        InvitationsRecord row = new InvitationsRecord();
        row.setId(TextLimits.require(INVITATIONS.ID, invitation.getId() != null ? invitation.getId() : EntityIds.next()));
        row.setEmail(TextLimits.require(INVITATIONS.EMAIL, EmailAddressUtil.requireNormalized(invitation.getEmail())));
        row.setRoleId(TextLimits.require(INVITATIONS.ROLE_ID, invitation.getRoleId()));
        row.setTokenHash(TextLimits.require(INVITATIONS.TOKEN_HASH, invitation.getTokenHash()));
        row.setExpiresAt(invitation.getExpiresAt());
        row.setCreatedAt(invitation.getCreatedAt());
        row.setUpdatedAt(invitation.getUpdatedAt());
        return row;
    }

    private static Invitation toInvitation(InvitationsRecord row) {
        return Invitation.builder()
            .id(row.getId())
            .email(row.getEmail())
            .roleId(row.getRoleId())
            .tokenHash(row.getTokenHash())
            .expiresAt(row.getExpiresAt())
            .createdAt(row.getCreatedAt())
            .updatedAt(row.getUpdatedAt())
            .build();
    }
}
