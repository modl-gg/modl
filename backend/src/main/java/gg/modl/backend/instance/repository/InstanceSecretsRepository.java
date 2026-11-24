package gg.modl.backend.instance.repository;

import gg.modl.backend.instance.InstanceSecrets;
import gg.modl.backend.instance.setup.IssuedSetupCode;
import java.time.Instant;
import java.util.Optional;

public interface InstanceSecretsRepository {
    InstanceSecrets ensure(String authCodeHashSecret, String storageSigningKey, Instant now);

    void storeSetupCode(IssuedSetupCode code);

    boolean consumeSetupCode(String codeHash, Instant now, int lockThreshold);

    Optional<Integer> recordFailedSetupCodeAttempt(Instant now);

    Optional<InstanceSecrets> findSetupCode();

    void clearSetupCode();
}
