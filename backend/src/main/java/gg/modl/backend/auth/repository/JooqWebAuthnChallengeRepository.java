package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CHALLENGES;

import gg.modl.backend.auth.data.WebAuthnChallenge;
import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.WebauthnChallengesRecord;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqWebAuthnChallengeRepository implements WebAuthnChallengeRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    @Override
    public void insert(WebAuthnChallenge challenge) {
        WebauthnChallengesRecord row = new WebauthnChallengesRecord(
            TextLimits.require(WEBAUTHN_CHALLENGES.ID, challenge.getId()),
            challenge.getChallengeJson(),
            AuthColumns.identityIfPresent(WEBAUTHN_CHALLENGES.EMAIL, challenge.getEmail()),
            challenge.getExpiresAt());
        unitOfWork.write(() -> dsl.insertInto(WEBAUTHN_CHALLENGES).set(row).execute());
    }

    @Override
    public Optional<WebAuthnChallenge> consumeActive(String challengeId, Instant now) {
        Condition active = ColumnLookups.matches(WEBAUTHN_CHALLENGES.ID, challengeId).and(WEBAUTHN_CHALLENGES.EXPIRES_AT.gt(now));
        return unitOfWork.write(() -> SingleUseRows.consume(dsl, WEBAUTHN_CHALLENGES, WEBAUTHN_CHALLENGES.ID, active))
            .map(JooqWebAuthnChallengeRepository::toChallenge);
    }

    @Override
    public int deleteByEmail(String email) {
        return unitOfWork.write(() -> dsl.deleteFrom(WEBAUTHN_CHALLENGES)
            .where(AuthColumns.matchesIdentity(WEBAUTHN_CHALLENGES.EMAIL, email))
            .execute());
    }

    private static WebAuthnChallenge toChallenge(WebauthnChallengesRecord row) {
        return new WebAuthnChallenge(row.getId(), row.getChallengeJson(), row.getEmail(), row.getExpiresAt());
    }
}
