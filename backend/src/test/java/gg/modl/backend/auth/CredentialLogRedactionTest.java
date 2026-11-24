package gg.modl.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.sql.ChildJvm;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialLogRedactionTest {
    @TempDir
    Path directory;

    @Test
    void aBootWithRootDebugLogsNeitherPasswordsNorAuthenticatorSecrets() throws Exception {
        try (ChildJvm server = ChildJvm.start(CredentialLogRedactionProcess.class, directory.toString())) {
            String verdict = server.awaitLineStartingWith(CredentialLogRedactionProcess.VERDICT);

            assertThat(verdict).isEqualTo(CredentialLogRedactionProcess.VERDICT + CredentialLogRedactionProcess.CLEAN);
        }
    }
}
