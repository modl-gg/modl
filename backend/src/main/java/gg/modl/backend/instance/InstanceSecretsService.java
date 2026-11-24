package gg.modl.backend.instance;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.repository.InstanceSecretsRepository;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InstanceSecretsService {
    private final InstanceSecretsRepository instanceSecretsRepository;
    private final UnitOfWork unitOfWork;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final AtomicReference<@Nullable InstanceSecrets> secrets = new AtomicReference<>();

    public void ensureInitialized() {
        secrets();
    }

    public String authCodeHashSecret() {
        return secrets().getAuthCodeHashSecret();
    }

    public String storageSigningKey() {
        return secrets().getStorageSigningKey();
    }

    private InstanceSecrets secrets() {
        InstanceSecrets current = secrets.get();
        return current != null ? current : storeAndCache();
    }

    private InstanceSecrets storeAndCache() {
        return unitOfWork.write(() -> {
            InstanceSecrets persisted = instanceSecretsRepository.ensure(
                idGenerator.generateToken(), idGenerator.generateToken(), clock.instant());
            unitOfWork.afterCommit(() -> secrets.compareAndSet(null, persisted));
            return persisted;
        });
    }
}
