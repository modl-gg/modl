package gg.modl.backend.instance.repository;

import static gg.modl.backend.database.jooq.Tables.INSTANCE_SECRETS;

import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.InstanceSecretsRecord;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeConditions;
import gg.modl.backend.instance.InstanceSecrets;
import gg.modl.backend.instance.setup.IssuedSetupCode;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.UpdateSetMoreStep;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqInstanceSecretsRepository implements InstanceSecretsRepository {
    private static final int SINGLETON_ID = 1;
    private static final Condition IS_INSTANCE = INSTANCE_SECRETS.ID.eq(SINGLETON_ID);
    private static final OneTimeCodeConditions.Columns SETUP_CODE = new OneTimeCodeConditions.Columns(
        INSTANCE_SECRETS.SETUP_CODE_HASH, INSTANCE_SECRETS.SETUP_CODE_EXPIRES_AT, INSTANCE_SECRETS.SETUP_CODE_FAILED_ATTEMPTS);

    private final DSLContext dsl;
    private final InsertIfAbsent insertIfAbsent;

    @Override
    public InstanceSecrets ensure(String authCodeHashSecret, String storageSigningKey, Instant now) {
        InstanceSecretsRecord row = new InstanceSecretsRecord();
        row.setId(SINGLETON_ID);
        row.setAuthCodeHashSecret(TextLimits.require(INSTANCE_SECRETS.AUTH_CODE_HASH_SECRET, authCodeHashSecret));
        row.setStorageSigningKey(TextLimits.require(INSTANCE_SECRETS.STORAGE_SIGNING_KEY, storageSigningKey));
        row.setSetupCodeFailedAttempts(0);
        row.setCreatedAt(now);
        insertIfAbsent.onPrimaryKey(INSTANCE_SECRETS).insert(row);
        return findSetupCode().orElseThrow(() -> new IllegalStateException("Instance secrets could not be created"));
    }

    @Override
    public void storeSetupCode(IssuedSetupCode code) {
        withCode(code).where(IS_INSTANCE).execute();
    }

    @Override
    public boolean consumeSetupCode(String codeHash, Instant now, int lockThreshold) {
        String boundHash = TextLimits.require(INSTANCE_SECRETS.SETUP_CODE_HASH, codeHash);
        return withoutCode()
            .where(OneTimeCodeConditions.activeUnlockedCode(IS_INSTANCE, SETUP_CODE, boundHash, now, lockThreshold))
            .execute() == 1;
    }

    @Override
    public Optional<Integer> recordFailedSetupCodeAttempt(Instant now) {
        boolean recorded = dsl.update(INSTANCE_SECRETS)
            .set(INSTANCE_SECRETS.SETUP_CODE_FAILED_ATTEMPTS, OneTimeCodeConditions.incrementedFailedAttempts(SETUP_CODE))
            .where(OneTimeCodeConditions.active(IS_INSTANCE, SETUP_CODE, now))
            .execute() == 1;
        return recorded ? findSetupCode().map(InstanceSecrets::getSetupCodeFailedAttempts) : Optional.empty();
    }

    @Override
    public Optional<InstanceSecrets> findSetupCode() {
        return dsl.selectFrom(INSTANCE_SECRETS)
            .where(IS_INSTANCE)
            .fetchOptional()
            .map(JooqInstanceSecretsRepository::toSecrets);
    }

    @Override
    public void clearSetupCode() {
        withoutCode().where(IS_INSTANCE).execute();
    }

    private UpdateSetMoreStep<InstanceSecretsRecord> withCode(IssuedSetupCode code) {
        return dsl.update(INSTANCE_SECRETS)
            .set(INSTANCE_SECRETS.SETUP_CODE_HASH, TextLimits.require(INSTANCE_SECRETS.SETUP_CODE_HASH, code.codeHash()))
            .set(INSTANCE_SECRETS.SETUP_CODE_EXPIRES_AT, code.expiresAt())
            .set(INSTANCE_SECRETS.SETUP_CODE_ISSUED_AT, code.issuedAt())
            .set(INSTANCE_SECRETS.SETUP_CODE_FAILED_ATTEMPTS, 0);
    }

    private UpdateSetMoreStep<InstanceSecretsRecord> withoutCode() {
        return dsl.update(INSTANCE_SECRETS)
            .setNull(INSTANCE_SECRETS.SETUP_CODE_HASH)
            .setNull(INSTANCE_SECRETS.SETUP_CODE_EXPIRES_AT)
            .set(INSTANCE_SECRETS.SETUP_CODE_FAILED_ATTEMPTS, 0);
    }

    private static InstanceSecrets toSecrets(InstanceSecretsRecord row) {
        return InstanceSecrets.builder()
            .id(InstanceSecrets.SINGLETON_ID)
            .authCodeHashSecret(row.getAuthCodeHashSecret())
            .storageSigningKey(row.getStorageSigningKey())
            .setupCodeHash(row.getSetupCodeHash())
            .setupCodeExpiresAt(row.getSetupCodeExpiresAt())
            .setupCodeIssuedAt(row.getSetupCodeIssuedAt())
            .setupCodeFailedAttempts(row.getSetupCodeFailedAttempts())
            .createdAt(row.getCreatedAt())
            .build();
    }
}
