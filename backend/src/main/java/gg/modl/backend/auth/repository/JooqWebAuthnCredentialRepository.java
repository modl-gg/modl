package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CREDENTIALS;

import gg.modl.backend.auth.data.WebAuthnCredential;
import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.Indexes;
import gg.modl.backend.database.jooq.tables.records.WebauthnCredentialsRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqWebAuthnCredentialRepository implements WebAuthnCredentialRepository {
    private static final long NO_SIGNATURE_COUNTER = 0L;

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;

    @Override
    public boolean insertIfAbsent(WebAuthnCredential credential) {
        WebauthnCredentialsRecord row = new WebauthnCredentialsRecord(
            TextLimits.require(WEBAUTHN_CREDENTIALS.ID, credential.getId()),
            AuthColumns.identity(WEBAUTHN_CREDENTIALS.EMAIL, credential.getEmail()),
            TextLimits.require(WEBAUTHN_CREDENTIALS.CREDENTIAL_ID, credential.getCredentialId()),
            credential.getPublicKeyCose(),
            credential.getSignatureCount(),
            TextLimits.require(WEBAUTHN_CREDENTIALS.USER_HANDLE, credential.getUserHandle()),
            TextLimits.require(WEBAUTHN_CREDENTIALS.NAME, credential.getName()),
            credential.getCreatedAt(),
            credential.getLastUsedAt());
        return unitOfWork.write(() -> insertIfAbsent
            .on(WEBAUTHN_CREDENTIALS, Indexes.UQ_WEBAUTHN_CREDENTIALS_CREDENTIAL_ID)
            .insert(row));
    }

    @Override
    public boolean existsByEmail(String email) {
        return dsl.fetchExists(WEBAUTHN_CREDENTIALS, emailIs(email));
    }

    @Override
    public int countByEmail(String email) {
        return dsl.fetchCount(WEBAUTHN_CREDENTIALS, emailIs(email));
    }

    @Override
    public Set<String> findEmails() {
        return Set.copyOf(dsl.selectDistinct(WEBAUTHN_CREDENTIALS.EMAIL).from(WEBAUTHN_CREDENTIALS).fetch(WEBAUTHN_CREDENTIALS.EMAIL));
    }

    @Override
    public List<WebAuthnCredential> findByEmail(String email) {
        return dsl.selectFrom(WEBAUTHN_CREDENTIALS)
            .where(emailIs(email))
            .orderBy(WEBAUTHN_CREDENTIALS.CREATED_AT, WEBAUTHN_CREDENTIALS.ID)
            .fetch()
            .map(JooqWebAuthnCredentialRepository::toCredential);
    }

    @Override
    public Optional<WebAuthnCredential> findByCredentialId(String credentialId) {
        return dsl.selectFrom(WEBAUTHN_CREDENTIALS)
            .where(credentialIdIs(credentialId))
            .fetchOptional()
            .map(JooqWebAuthnCredentialRepository::toCredential);
    }

    @Override
    public Optional<WebAuthnCredential> findByUserHandle(String userHandle) {
        return dsl.selectFrom(WEBAUTHN_CREDENTIALS)
            .where(ColumnLookups.matches(WEBAUTHN_CREDENTIALS.USER_HANDLE, userHandle))
            .orderBy(WEBAUTHN_CREDENTIALS.CREATED_AT, WEBAUTHN_CREDENTIALS.ID)
            .limit(1)
            .fetchOptional()
            .map(JooqWebAuthnCredentialRepository::toCredential);
    }

    @Override
    public boolean updateUsage(String credentialId, long signatureCount, Instant lastUsedAt) {
        Condition advancesCounter = signatureCount == NO_SIGNATURE_COUNTER
            ? WEBAUTHN_CREDENTIALS.SIGNATURE_COUNT.eq(signatureCount)
            : WEBAUTHN_CREDENTIALS.SIGNATURE_COUNT.lt(signatureCount);
        return unitOfWork.write(() -> dsl.update(WEBAUTHN_CREDENTIALS)
            .set(WEBAUTHN_CREDENTIALS.SIGNATURE_COUNT, signatureCount)
            .set(WEBAUTHN_CREDENTIALS.LAST_USED_AT, lastUsedAt)
            .where(credentialIdIs(credentialId))
            .and(advancesCounter)
            .execute() == 1);
    }

    @Override
    public boolean renameByIdAndEmail(String id, String email, String newName) {
        String name = TextLimits.require(WEBAUTHN_CREDENTIALS.NAME, newName.trim());
        return unitOfWork.write(() -> dsl.update(WEBAUTHN_CREDENTIALS)
            .set(WEBAUTHN_CREDENTIALS.NAME, name)
            .where(ownedCredential(id, email))
            .execute() == 1);
    }

    @Override
    public boolean deleteByIdAndEmail(String id, String email) {
        return unitOfWork.write(() -> dsl.deleteFrom(WEBAUTHN_CREDENTIALS).where(ownedCredential(id, email)).execute() == 1);
    }

    @Override
    public long deleteAllByEmail(String email) {
        int deleted = unitOfWork.write(() -> dsl.deleteFrom(WEBAUTHN_CREDENTIALS).where(emailIs(email)).execute());
        return deleted;
    }

    private static Condition ownedCredential(String id, String email) {
        return ColumnLookups.matches(WEBAUTHN_CREDENTIALS.ID, id).and(emailIs(email));
    }

    private static Condition credentialIdIs(String credentialId) {
        return ColumnLookups.matches(WEBAUTHN_CREDENTIALS.CREDENTIAL_ID, credentialId);
    }

    private static Condition emailIs(String email) {
        return AuthColumns.matchesIdentity(WEBAUTHN_CREDENTIALS.EMAIL, email);
    }

    private static WebAuthnCredential toCredential(WebauthnCredentialsRecord row) {
        return new WebAuthnCredential(row.getId(), row.getEmail(), row.getCredentialId(), row.getPublicKeyCose(),
            row.getSignatureCount(), row.getUserHandle(), row.getName(), row.getCreatedAt(), row.getLastUsedAt());
    }
}
