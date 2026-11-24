package gg.modl.backend.serverkey.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServerKeySecretsTest {
    private final ServerKeySecrets secrets = new ServerKeySecrets();

    @Test
    void aSecretCarriesThePrefixAndThirtyTwoRandomBytes() {
        ServerKeySecrets.IssuedSecret issued = secrets.issue();

        assertThat(issued.secret()).startsWith(ServerKeySecrets.PREFIX).hasSize(ServerKeySecrets.PREFIX.length() + 43);
        assertThat(issued.secret().substring(ServerKeySecrets.PREFIX.length())).matches("[A-Za-z0-9_-]+");
        assertThat(ServerKeySecrets.looksLikeServerKey(issued.secret())).isTrue();
    }

    @Test
    void theHashIsLowerCaseHexSha256AndTheHintIsTheStartOfTheSecret() {
        ServerKeySecrets.IssuedSecret issued = secrets.issue();

        assertThat(issued.hash()).matches("[0-9a-f]{64}").isEqualTo(ServerKeySecrets.hash(issued.secret()));
        assertThat(issued.hint()).hasSize(ServerKeySecrets.HINT_LENGTH)
            .isEqualTo(issued.secret().substring(ServerKeySecrets.PREFIX.length(), ServerKeySecrets.PREFIX.length() + 8));
    }

    @Test
    void secretsDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < 1000; index++) {
            assertThat(seen.add(secrets.issue().secret())).isTrue();
        }
    }

    @Test
    void theSecretNeverAppearsInItsDescription() {
        ServerKeySecrets.IssuedSecret issued = secrets.issue();

        assertThat(issued.toString()).doesNotContain(issued.secret()).doesNotContain(issued.hash());
    }

    @Test
    void onlyWellFormedServerKeysAreLookedUp() {
        assertThat(ServerKeySecrets.looksLikeServerKey("modl_instance-key")).isFalse();
        assertThat(ServerKeySecrets.looksLikeServerKey("msk_short")).isFalse();
        assertThat(ServerKeySecrets.looksLikeServerKey("msk_" + "a".repeat(200))).isFalse();
    }
}
