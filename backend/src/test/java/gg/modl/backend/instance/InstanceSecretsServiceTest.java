package gg.modl.backend.instance;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.repository.JooqInstanceSecretsRepository;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceSecretsServiceTest {
    @TempDir
    Path dataDirectory;

    @Test
    void theSecretsHandedOutAreTheCommittedOnesAfterARetryAndARestart() {
        try (SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, dataDirectory, new RetryInjection())) {
            JooqInstanceSecretsRepository repository = new JooqInstanceSecretsRepository(database.dsl(), database.insertIfAbsent());
            InstanceSecretsService first = new InstanceSecretsService(repository, database.unitOfWork(), new IdGenerator(), database.clock());

            first.ensureInitialized();

            InstanceSecrets stored = repository.findSetupCode().orElseThrow();
            assertThat(first.authCodeHashSecret()).isEqualTo(stored.getAuthCodeHashSecret());
            assertThat(first.storageSigningKey()).isEqualTo(stored.getStorageSigningKey());
            InstanceSecretsService restarted = new InstanceSecretsService(repository, database.unitOfWork(), new IdGenerator(), database.clock());
            assertThat(restarted.authCodeHashSecret()).isEqualTo(stored.getAuthCodeHashSecret());
        }
    }
}
